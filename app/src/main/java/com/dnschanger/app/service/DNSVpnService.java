package com.dnschanger.app.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.dnschanger.app.ui.MainActivity;
import com.dnschanger.app.utils.DNSPacketHandler;
import com.dnschanger.app.utils.PreferenceManager;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;

/**
 * DNSVpnService
 * 
 * Service VPN yang mengarahkan seluruh traffic DNS (port 53) ke DNS server
 * yang dipilih pengguna. Kompatibel dengan Android 5.1 (API 22) ke atas.
 * 
 * Cara kerja:
 * 1. Membuat interface VPN lokal (tun0)
 * 2. Menangkap paket UDP port 53 (DNS query)
 * 3. Meneruskan ke DNS server yang dipilih
 * 4. Mengembalikan response ke aplikasi asal
 */
public class DNSVpnService extends VpnService {

    private static final String TAG = "DNSVpnService";
    public static final String ACTION_START = "com.dnschanger.START";
    public static final String ACTION_STOP  = "com.dnschanger.STOP";

    public static final int NOTIFICATION_ID = 1001;
    private static final int VPN_MTU         = 1500;
    private static final String VPN_ADDRESS  = "10.0.0.2";   // IP virtual interface
    private static final String VPN_ROUTE    = "0.0.0.0";    // Route semua traffic
    private static final int    DNS_PORT     = 53;

    private ParcelFileDescriptor vpnInterface = null;
    private Thread               vpnThread   = null;
    private volatile boolean     running     = false;

    private String dns1 = "8.8.8.8";
    private String dns2 = "8.8.4.4";

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            return START_NOT_STICKY;
        }

        // Ambil DNS dari preferensi
        PreferenceManager prefs = new PreferenceManager(this);
        dns1 = prefs.getDns1();
        dns2 = prefs.getDns2();

        startVpn();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ //
    //  VPN Control
    // ------------------------------------------------------------------ //

    private void startVpn() {
        if (running) return;

        // Bangun interface VPN
        Builder builder = new Builder();
        builder.setMtu(VPN_MTU);
        builder.addAddress(VPN_ADDRESS, 32);
        builder.addRoute(VPN_ROUTE, 0);
        builder.addDnsServer(dns1);
        if (dns2 != null && !dns2.isEmpty()) {
            builder.addDnsServer(dns2);
        }
        builder.setSession("DNS Changer");

        // Intent untuk membuka app saat notifikasi ditekan
        Intent configIntent = new Intent(this, MainActivity.class);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pi = PendingIntent.getActivity(this, 0, configIntent,
                    PendingIntent.FLAG_IMMUTABLE);
        } else {
            pi = PendingIntent.getActivity(this, 0, configIntent, 0);
        }
        builder.setConfigureIntent(pi);

        try {
            vpnInterface = builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Gagal membuat VPN interface", e);
            broadcastStatus(false, "Gagal membuat VPN: " + e.getMessage());
            return;
        }

        if (vpnInterface == null) {
            broadcastStatus(false, "VPN interface null - cek izin VPN");
            return;
        }

        running = true;

        // Tampilkan notifikasi foreground (wajib sejak Android O, tapi aman untuk 5.1+)
        showNotification();

        // Mulai thread pembaca/penerus paket
        vpnThread = new Thread(this::runVpnLoop, "dns-vpn-thread");
        vpnThread.start();

        broadcastStatus(true, "VPN aktif – DNS: " + dns1);
        Log.i(TAG, "VPN started. DNS1=" + dns1 + " DNS2=" + dns2);
    }

    private void stopVpn() {
        running = false;

        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }

        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }

        stopForeground(true);
        stopSelf();

        broadcastStatus(false, "VPN dihentikan");
        Log.i(TAG, "VPN stopped");
    }

    // ------------------------------------------------------------------ //
    //  Packet Loop
    // ------------------------------------------------------------------ //

    /**
     * Loop utama: baca paket dari tun interface,
     * jika itu paket DNS → forward ke server DNS pilihan,
     * kembalikan response ke tun interface.
     */
    private void runVpnLoop() {
        FileInputStream  in  = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

        ByteBuffer packet = ByteBuffer.allocate(VPN_MTU);

        while (running) {
            try {
                packet.clear();
                int length = in.read(packet.array());
                if (length <= 0) continue;

                packet.limit(length);

                // Periksa apakah ini paket IPv4 UDP ke port 53
                if (!DNSPacketHandler.isDnsQuery(packet.array(), length)) {
                    // Bukan DNS, teruskan apa adanya (tidak kita proses)
                    out.write(packet.array(), 0, length);
                    continue;
                }

                // Forward DNS query ke server DNS yang dipilih
                byte[] dnsPayload = DNSPacketHandler.extractUdpPayload(packet.array(), length);
                if (dnsPayload == null) continue;

                byte[] dnsResponse = forwardDnsQuery(dnsPayload);
                if (dnsResponse == null) continue;

                // Bungkus response kembali menjadi paket IP/UDP
                byte[] responsePacket = DNSPacketHandler.buildDnsResponsePacket(
                        packet.array(), length, dnsResponse);
                if (responsePacket != null) {
                    out.write(responsePacket);
                }

            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "Packet loop error: " + e.getMessage());
                }
            }
        }

        try { in.close();  } catch (IOException ignored) {}
        try { out.close(); } catch (IOException ignored) {}
    }

    /**
     * Kirim DNS query ke server DNS pilihan dan kembalikan response-nya.
     */
    private byte[] forwardDnsQuery(byte[] query) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket); // PENTING: agar socket tidak dirutekan melalui VPN (loop!)

            InetAddress dnsAddress = InetAddress.getByName(dns1);
            DatagramPacket sendPacket = new DatagramPacket(
                    query, query.length, dnsAddress, DNS_PORT);
            socket.send(sendPacket);

            byte[] response = new byte[VPN_MTU];
            DatagramPacket recvPacket = new DatagramPacket(response, response.length);
            socket.setSoTimeout(5000);
            socket.receive(recvPacket);

            byte[] result = new byte[recvPacket.getLength()];
            System.arraycopy(response, 0, result, 0, recvPacket.getLength());
            return result;

        } catch (Exception e) {
            Log.w(TAG, "DNS forward error: " + e.getMessage());
            // Coba DNS kedua jika ada
            if (dns2 != null && !dns2.isEmpty()) {
                return forwardDnsQueryTo(query, dns2);
            }
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    private byte[] forwardDnsQueryTo(byte[] query, String dnsServer) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            protect(socket);
            InetAddress addr = InetAddress.getByName(dnsServer);
            socket.send(new DatagramPacket(query, query.length, addr, DNS_PORT));
            byte[] buf = new byte[VPN_MTU];
            DatagramPacket recv = new DatagramPacket(buf, buf.length);
            socket.setSoTimeout(5000);
            socket.receive(recv);
            byte[] res = new byte[recv.getLength()];
            System.arraycopy(buf, 0, res, 0, recv.getLength());
            return res;
        } catch (Exception e) {
            Log.w(TAG, "DNS fallback error: " + e.getMessage());
            return null;
        } finally {
            if (socket != null) socket.close();
        }
    }

    // ------------------------------------------------------------------ //
    //  Notification (compatible with API 22 / Android 5.1)
    // ------------------------------------------------------------------ //

    @SuppressWarnings("deprecation")
    private void showNotification() {
        Intent stopIntent = new Intent(this, DNSVpnService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            stopPi = PendingIntent.getService(this, 0, stopIntent,
                    PendingIntent.FLAG_IMMUTABLE);
        } else {
            stopPi = PendingIntent.getService(this, 0, stopIntent, 0);
        }

        // Gunakan Notification.Builder biasa (API 22 belum ada NotificationCompat wajib)
        Notification.Builder nb = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("DNS Changer Aktif")
                .setContentText("DNS: " + dns1)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPi);

        Notification notification = nb.build();
        startForeground(NOTIFICATION_ID, notification);
    }

    // ------------------------------------------------------------------ //
    //  Broadcast status ke MainActivity
    // ------------------------------------------------------------------ //

    private void broadcastStatus(boolean active, String message) {
        Intent intent = new Intent(MainActivity.ACTION_VPN_STATUS);
        intent.putExtra("active", active);
        intent.putExtra("message", message);
        sendBroadcast(intent);
    }
}
