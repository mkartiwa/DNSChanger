package com.dnschanger.app.utils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * DNSPacketHandler
 *
 * Helper untuk:
 * - Mendeteksi apakah paket IP adalah DNS query (UDP port 53)
 * - Mengekstrak payload UDP dari paket IPv4
 * - Membungkus DNS response menjadi paket IPv4/UDP yang valid
 *
 * Format paket IPv4:
 *   Byte 0-19  : IP header (20 byte minimal)
 *   Byte 20-27 : UDP header (8 byte)
 *   Byte 28+   : UDP payload (DNS message)
 *
 * Referensi: RFC 791 (IPv4), RFC 768 (UDP), RFC 1035 (DNS)
 */
public class DNSPacketHandler {

    // Offset dalam paket IPv4
    private static final int IP_VERSION_IHL   = 0;
    private static final int IP_PROTO         = 9;   // Protocol (17 = UDP)
    private static final int IP_SRC_ADDR      = 12;
    private static final int IP_DST_ADDR      = 16;

    // Offset dalam header UDP (relatif ke awal IP header)
    private static final int UDP_SRC_PORT_OFFSET = 0;
    private static final int UDP_DST_PORT_OFFSET = 2;
    private static final int UDP_LENGTH_OFFSET   = 4;
    private static final int UDP_CHECKSUM_OFFSET = 6;
    private static final int UDP_HEADER_SIZE     = 8;

    private static final int PROTO_UDP  = 17;
    private static final int PORT_DNS   = 53;

    // ------------------------------------------------------------------ //
    //  Public API
    // ------------------------------------------------------------------ //

    /**
     * Kembalikan true jika paket adalah IPv4 UDP yang ditujukan ke port 53.
     */
    public static boolean isDnsQuery(byte[] packet, int length) {
        if (length < 28) return false;  // Minimal IP(20) + UDP(8)

        // Cek IP version = 4
        int versionIhl = packet[IP_VERSION_IHL] & 0xFF;
        int version    = (versionIhl >> 4) & 0xF;
        if (version != 4) return false;

        // Cek protocol = UDP
        int proto = packet[IP_PROTO] & 0xFF;
        if (proto != PROTO_UDP) return false;

        // Hitung panjang IP header
        int ipHeaderLen = (versionIhl & 0xF) * 4;
        if (length < ipHeaderLen + UDP_HEADER_SIZE) return false;

        // Cek destination port = 53
        int dstPort = getUint16(packet, ipHeaderLen + UDP_DST_PORT_OFFSET);
        return dstPort == PORT_DNS;
    }

    /**
     * Ekstrak payload DNS (setelah header IP dan UDP).
     */
    public static byte[] extractUdpPayload(byte[] packet, int length) {
        if (!isDnsQuery(packet, length)) return null;

        int ipHeaderLen   = ((packet[IP_VERSION_IHL] & 0xF)) * 4;
        int udpPayloadOff = ipHeaderLen + UDP_HEADER_SIZE;
        int udpLen        = getUint16(packet, ipHeaderLen + UDP_LENGTH_OFFSET);
        int payloadLen    = udpLen - UDP_HEADER_SIZE;

        if (payloadLen <= 0 || udpPayloadOff + payloadLen > length) return null;

        return Arrays.copyOfRange(packet, udpPayloadOff, udpPayloadOff + payloadLen);
    }

    /**
     * Buat paket IPv4/UDP berisi DNS response.
     * Source/dest address & port dibalik dari paket request asli.
     */
    public static byte[] buildDnsResponsePacket(byte[] originalRequest, int origLen,
                                                 byte[] dnsResponse) {
        if (originalRequest == null || dnsResponse == null) return null;

        int ipHeaderLen = ((originalRequest[IP_VERSION_IHL] & 0xF)) * 4;

        // Ekstrak info dari request
        byte[] srcAddr = Arrays.copyOfRange(originalRequest, IP_SRC_ADDR, IP_SRC_ADDR + 4);
        byte[] dstAddr = Arrays.copyOfRange(originalRequest, IP_DST_ADDR, IP_DST_ADDR + 4);
        int    srcPort = getUint16(originalRequest, ipHeaderLen + UDP_SRC_PORT_OFFSET);
        int    dstPort = getUint16(originalRequest, ipHeaderLen + UDP_DST_PORT_OFFSET);

        // Ukuran total response packet
        int totalLen = 20 + UDP_HEADER_SIZE + dnsResponse.length;
        byte[] resp = new byte[totalLen];

        // --- Isi IP header (20 byte) ---
        resp[0] = (byte) 0x45;  // Version=4, IHL=5
        resp[1] = 0;            // DSCP/ECN
        setUint16(resp, 2, totalLen);
        setUint16(resp, 4, 0);  // ID
        setUint16(resp, 6, 0x4000);  // Flags: Don't Fragment
        resp[8] = 64;           // TTL
        resp[9] = PROTO_UDP;    // Protocol

        // Source = original destination (DNS server → app)
        // Destination = original source (app)
        System.arraycopy(dstAddr, 0, resp, IP_SRC_ADDR, 4);
        System.arraycopy(srcAddr, 0, resp, IP_DST_ADDR, 4);

        // Hitung IP checksum
        setUint16(resp, 10, 0);
        setUint16(resp, 10, ipChecksum(resp, 0, 20));

        // --- Isi UDP header (8 byte) ---
        int udpOff = 20;
        setUint16(resp, udpOff + UDP_SRC_PORT_OFFSET, dstPort);  // port 53
        setUint16(resp, udpOff + UDP_DST_PORT_OFFSET, srcPort);  // port aplikasi
        int udpLen = UDP_HEADER_SIZE + dnsResponse.length;
        setUint16(resp, udpOff + UDP_LENGTH_OFFSET, udpLen);
        setUint16(resp, udpOff + UDP_CHECKSUM_OFFSET, 0);  // checksum opsional di IPv4

        // --- Isi DNS payload ---
        System.arraycopy(dnsResponse, 0, resp, udpOff + UDP_HEADER_SIZE, dnsResponse.length);

        return resp;
    }

    // ------------------------------------------------------------------ //
    //  Private helpers
    // ------------------------------------------------------------------ //

    private static int getUint16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static void setUint16(byte[] data, int offset, int value) {
        data[offset]     = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    /**
     * Hitung Internet Checksum (RFC 1071) untuk header IP.
     */
    private static int ipChecksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += getUint16(data, i);
        }
        if ((length & 1) != 0) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }
}
