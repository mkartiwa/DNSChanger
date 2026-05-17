package com.dnschanger.app.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.dnschanger.app.utils.PreferenceManager;

/**
 * BootReceiver
 * Menerima broadcast BOOT_COMPLETED dan menghidupkan kembali VPN
 * jika pengguna mengaktifkan opsi "Auto Start".
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        PreferenceManager prefs = new PreferenceManager(context);
        if (prefs.isAutoStart()) {
            // Catatan: VpnService.prepare() memerlukan Activity, jadi kita hanya bisa
            // memulai ulang VPN jika izin sudah pernah diberikan sebelumnya.
            Intent vpnIntent = new Intent(context, DNSVpnService.class);
            vpnIntent.setAction(DNSVpnService.ACTION_START);
            context.startService(vpnIntent);
        }
    }
}
