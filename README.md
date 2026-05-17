# 🛡 DNS Changer via VPN
**Aplikasi Android untuk mengubah DNS melalui VPN lokal**  
Kompatibel dengan **Android 5.1 Lollipop (API 22)** ke atas

---

## 📋 Fitur

| Fitur | Keterangan |
|-------|-----------|
| VPN Lokal | Menggunakan `VpnService` bawaan Android, tanpa server eksternal |
| Preset DNS | Google, Cloudflare, OpenDNS, Quad9, AdGuard, Comodo |
| DNS Kustom | Input IP DNS sendiri |
| Fallback DNS | Jika DNS utama gagal, otomatis coba DNS cadangan |
| Auto Start | Aktif kembali otomatis setelah reboot |
| Notifikasi | Notifikasi foreground persisten selama VPN aktif |
| Tombol Stop | Bisa stop langsung dari notifikasi |

---

## 🏗 Struktur Proyek

```
DNSChanger/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dnschanger/app/
│       │   ├── service/
│       │   │   ├── DNSVpnService.java     ← Core VPN + DNS forwarding
│       │   │   └── BootReceiver.java      ← Auto-start setelah reboot
│       │   ├── ui/
│       │   │   └── MainActivity.java      ← Tampilan utama
│       │   └── utils/
│       │       ├── DNSPacketHandler.java  ← Parser paket IP/UDP/DNS
│       │       └── PreferenceManager.java ← Simpan pengaturan
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           ├── values/styles.xml
│           └── drawable/
│               ├── edittext_bg.xml
│               └── spinner_bg.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## 🔧 Cara Kerja (Teknis)

```
Aplikasi → [VPN Interface tun0] → DNSVpnService
                                        │
               ┌────────────────────────┘
               │  Tangkap paket UDP port 53
               │  Ekstrak DNS query
               │  Forward ke DNS server pilihan
               │  Kembalikan response ke app
               └────────────────────────┐
Aplikasi ← [VPN Interface tun0] ←──────┘
```

1. `VpnService.Builder` membuat interface virtual **tun0**
2. Semua traffic DNS (UDP port 53) ditangkap oleh `DNSVpnService`
3. `DNSPacketHandler` mem-parse header IPv4 + UDP
4. Query diteruskan ke DNS server menggunakan `DatagramSocket` yang di-`protect()` (agar tidak loop)
5. Response dikemas ulang menjadi paket IPv4/UDP dan dikembalikan ke aplikasi

---

## 🚀 Cara Build & Install

### Prasyarat
- **Android Studio** 3.5 atau lebih baru
- **JDK 8**
- **Android SDK** dengan Build Tools 28.x

### Langkah Build

```bash
# 1. Clone / extract proyek
cd DNSChanger

# 2. Build debug APK
./gradlew assembleDebug

# 3. Lokasi APK
# app/build/outputs/apk/debug/app-debug.apk

# 4. Install ke device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Build via Android Studio
1. Buka **Android Studio** → `File > Open` → pilih folder `DNSChanger`
2. Tunggu Gradle sync selesai
3. Tekan ▶ **Run** atau `Build > Build Bundle(s)/APK(s) > Build APK`

---

## 📱 Cara Penggunaan

1. **Buka aplikasi**
2. **Pilih preset DNS** dari dropdown (Google, Cloudflare, dll.) atau ketik manual
3. **Tekan START VPN**
4. Dialog izin VPN Android akan muncul → tekan **OK**
5. Status berubah menjadi **aktif** (indikator hijau)
6. Untuk menghentikan: tekan **STOP VPN** atau tombol Stop di notifikasi

---

## 🔑 Izin yang Dibutuhkan

| Izin | Alasan |
|------|--------|
| `INTERNET` | Meneruskan paket DNS ke server |
| `ACCESS_NETWORK_STATE` | Deteksi koneksi jaringan |
| `FOREGROUND_SERVICE` | Notifikasi foreground wajib |
| `RECEIVE_BOOT_COMPLETED` | Auto-start setelah reboot |
| VPN Permission | Dialog pop-up sistem, **bukan** izin manifest biasa |

---

## 🌐 Daftar Preset DNS

| Nama | DNS Utama | DNS Cadangan | Keunggulan |
|------|-----------|--------------|------------|
| Google | 8.8.8.8 | 8.8.4.4 | Cepat, andal |
| Cloudflare | 1.1.1.1 | 1.0.0.1 | Privasi tinggi, tercepat |
| OpenDNS | 208.67.222.222 | 208.67.220.220 | Filter konten |
| Quad9 | 9.9.9.9 | 149.112.112.112 | Blokir malware |
| AdGuard | 94.140.14.14 | 94.140.15.15 | Blokir iklan + tracker |
| Comodo | 8.26.56.26 | 8.20.247.20 | Keamanan ekstra |

---

## ⚠️ Catatan Penting

- Aplikasi **tidak memerlukan root**
- VPN berjalan **lokal di device** — tidak ada data yang dikirim ke server pihak ketiga
- Pada beberapa ROM / device, `VpnService` mungkin memerlukan pengaturan tambahan di System Settings
- Auto-start setelah reboot memerlukan izin VPN yang sudah pernah diberikan sebelumnya (Android tidak mengizinkan grant VPN permission tanpa interaksi pengguna)
- Diuji pada Android 5.1 (API 22), 6.0, 7.0, 8.0, 9.0, 10

---

## 📄 Lisensi
MIT License — Bebas digunakan dan dimodifikasi
