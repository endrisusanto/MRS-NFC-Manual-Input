# Bridge MeRS (MeRS NFC & Remote System) 🚀

[![Version](https://img.shields.io/badge/version-1.0.108-blue.svg)](https://github.com/endrisusanto/MRS-NFC-Manual-Input)
[![Tauri](https://img.shields.io/badge/Tauri-v2-orange.svg)](https://tauri.app/)
[![Android](https://img.shields.io/badge/Android-Kotlin-green.svg)](https://developer.android.com/)
[![Manifest](https://img.shields.io/badge/Manifest-V3-brightgreen.svg)](https://developer.chrome.com/docs/extensions/mv3/intro/)
[![Style](https://img.shields.io/badge/Style-Neobrutalism%20%26%20Glassmorphism-yellow.svg)](#)

Solusi multi-platform (Desktop Agent, Web PWA, Android APK, dan Chrome Extension) untuk menjembatani operasional sistem katering/kantin **MeRS Intranet** (`https://seinp.sec.samsung.net/MERS`). Memungkinkan tap in NFC, input manual UID/GEN, pemesanan & pembatalan menu, pengecekan riwayat pesanan, sinkronisasi widget Android, hingga relay jarak jauh melalui Cloud WebSocket Gateway.

---

## 🌟 Komponen & Fitur Utama

### 1. 🖥️ Desktop Agent (Tauri / Rust)
- **Direct Intranet Access**: Menghubungkan langsung PC kasir/loket ke backend `https://seinp.sec.samsung.net/MERS`.
- **High-Performance Bridge**: Menghubungkan cloud WebSocket gateway (`wss://makan.endrisusanto.my.id`) dengan intranet lokal.
- **Resilient Background Loop**:
  - Worker asynchronous non-blocking untuk memproses perintah tanpa menghentikan loop jaringan.
  - Heartbeat & ping aktif setiap 15 detik untuk mencegah silent TCP disconnect dari Cloudflare/Nginx.
  - Watchdog 60 detik dengan auto-reconnect cepat (3 detik).
- **TLS Tolerance**: Mendukung handshake HTTPS internal intranet Samsung tanpa error sertifikat.
- **System Tray & Hot Reload**: Berjalan di background/tray dan mendukung pembaruan konfigurasi dinamis.

### 2. 📱 Web Remote & PWA (`web/`)
- **Mobile-First & PWA Installable**: Dapat diakses dan diinstal di smartphone atau browser mana saja.
- **Cek & Tap In Pesanan**: Pengecekan pesanan aktif dan eksekusi pengambilan makanan (Tap In) per loket.
- **🍱 Menu Order & Cancel**:
  - Melihat menu makan siang & makan malam multi-hari dengan status stok real-time.
  - Submit pesanan katering dan pembatalan pesanan langsung dari UI.
- **📋 Riwayat Pesanan**: Melihat log pesanan yang sudah diambil maupun yang belum diambil.
- **Web NFC & UID Converter**: Membaca kartu via Web NFC (Chrome Android) dengan reverse-byte converter otomatis.

### 3. 🤖 Android Native App & Home Screen Widgets (`android/`)
- **Native NFC Reader**: Membaca UID chip kartu NFC secara presisi dan mengirimkan ke antarmuka aplikasi.
- **Home Screen Widgets**:
  - **Widget 4x2**: Menampilkan ringkasan menu hari ini, status pengambilan, tombol ganti jadwal, dan tombol refresh.
  - **Widget 2x2**: Tampilan kompak dengan info menu utama dan status real-time.
- **Background Sync Worker**: Widget diperbarui secara periodik di background melalui proxy cloud.

### 4. 🧩 Browser Extension (Manifest V3)
- **Manual UID / Scanner Helper**: Membantu input UID secara manual saat hardware NFC reader di PC kasir tidak terdeteksi.
- **📌 Pinned & Recent IDs**: Menyimpan daftar ID karyawan yang sering digunakan.
- **Bento Preview & Quota Monitor**: Menampilkan rincian menu bento dan kuota porsi yang tersisa.

---

## ⚙️ URL Sistem & Konfigurasi

| Layanan | URL Default | Deskripsi |
|---|---|---|
| **MeRS Intranet** | `https://seinp.sec.samsung.net/MERS` | Endpoint intranet utama (HTTPS) |
| **Cloud Gateway** | `wss://makan.endrisusanto.my.id` | WebSocket relay untuk komunikasi jarak jauh |
| **Web App PWA** | `https://makan.endrisusanto.my.id/` | Frontend Web / PWA Remote |

---

## 🛠️ Panduan Build & Menjalankan Aplikasi

### Desktop App (Tauri / Rust)
```bash
npm install
npm run dev
```
Build installer MSI Windows:
```bash
npm run dist:win
```

### Web PWA Remote
```bash
npm run web:dev
```
Jalankan via Docker:
```bash
docker compose up -d --build
```
Akses di: `http://localhost:7465`

### Android APK
```bash
npm run android:debug
```
Output APK berada di `android/app/build/outputs/apk/debug/app-debug.apk`.

### Chrome Extension
1. Buka `chrome://extensions/` di browser Chromium (Chrome/Edge).
2. Aktifkan **Developer Mode**.
3. Klik **Load unpacked** dan pilih folder root repository ini.

---

## 📦 Rilis Otomatis

```bash
./release.sh
```
Script akan menaikkan versi patch, membuat git tag, dan melakukan push ke repository. GitHub Actions akan otomatis mengompilasi file installer Windows MSI, Android APK, dan bundle ZIP Chrome Extension.

---

## 🛡️ Keamanan & Privasi

- Kredensial dan PIN lokal tersimpan dengan enkripsi pada perangkat pengguna (`localStorage` / `SharedPreferences`).
- Komunikasi cloud menggunakan enkripsi TLS/HTTPS & WSS.

---

Dibuat dengan ❤️ untuk efisiensi operasional.
Copyright © 2026 - [Endri Susanto](https://github.com/endrisusanto)
