# MRS NFC Manual Input 🚀

[![Version](https://img.shields.io/badge/version-1.0-blue.svg)](https://github.com/endrisusanto/MRS-NFC-Manual-Input)
[![Manifest](https://img.shields.io/badge/Manifest-V3-green.svg)](https://developer.chrome.com/docs/extensions/mv3/intro/)
[![Style](https://img.shields.io/badge/Style-Neobrutalism-yellow.svg)](#)

Extension browser untuk memudahkan input UID NFC secara manual pada sistem **MeRS**. Didesain khusus untuk situasi di mana hardware NFC Reader tidak tersedia atau tidak terdeteksi, memungkinkan pengujian dan operasional tetap berjalan dengan input manual.

## ✨ Fitur Utama

- **Manual UID/Serial Input**: Masukkan nomor kartu dalam format desimal maupun hex (serial) dengan mudah.
- **📌 Pinned IDs**: Simpan ID yang sering digunakan dengan sistem sematan (pinning) agar tidak perlu mengetik berulang kali.
- **🕒 Riwayat Input**: Melacak ID terakhir yang digunakan secara otomatis.
- **🎨 Neobrutalism UI**: Antarmuka modern, kontras tinggi, dan responsif.
- **⚡ Quick Actions**: Tombol akses cepat ke halaman Scanner dan Menu utama MRS.
- **🔄 Auto-Injection**: Panel input manual otomatis muncul di halaman scanner jika extension aktif.

## 🛠️ Instalasi (Developer Mode)

Karena extension ini belum dipublikasikan di Chrome Web Store, Anda dapat menginstalnya secara manual:

1. **Clone** repository ini atau **Download ZIP** dan ekstrak.
2. Buka browser Chrome/Edge dan arahkan ke `chrome://extensions/`.
3. Aktifkan **Developer Mode** di pojok kanan atas.
4. Klik tombol **Load unpacked** (Muat yang belum dikemas).
5. Pilih folder tempat Anda menyimpan file project ini.
6. Icon MRS NFC akan muncul di toolbar browser Anda.

## 🚀 Cara Penggunaan

### Melalui Popup Extension
1. Klik icon extension di toolbar.
2. Masukkan UID atau Serial kartu pada kolom input.
3. Klik tombol **🔍 TAP IN MeRS!** untuk mengirim data ke halaman scanner yang aktif.
4. Gunakan icon 📌 untuk menyimpan ID tersebut ke daftar favorit.

### Melalui Floating Modal di Halaman
Saat Anda berada di halaman `nfc_scanner.html`, sebuah tombol melayang (atau panel) akan tersedia secara otomatis untuk memudahkan input tanpa harus membuka popup extension.

## 💻 Tech Stack

- **Javascript (ES6+)**: Logika utama extension dan komunikasi antar script.
- **HTML5 & CSS3**: Struktur panel dan styling Neobrutalism modern.
- **Chrome Extension API (V3)**: Storage API untuk persistensi data dan Scripting API untuk injeksi.
- **Tauri/Rust**: Desktop client MeRS dengan UI dark glassmorphism, NSIS installer, dan algoritma bridge yang sama.

## 🖥️ Desktop App

```bash
npm install
npm run dev
```

UI desktop memiliki input GEN 8 digit atau ID kartu 10 digit, shortcut **Tap In Scanner**, shortcut **Cek Menu Pesanan**, dan pilihan 6 loket untuk Tap In.

Desktop app memanggil backend `http://107.102.8.148/MERS` langsung dari PC tempat app dijalankan. Build bisa dibuat dari jaringan mana pun, tetapi fitur cek pesanan dan tap in hanya bisa dites/berjalan di PC yang punya akses intranet ke alamat tersebut.

## Debug Workflow MeRS

Di PC intranet yang menjalankan extension, buka halaman MeRS, jalankan workflow scanner/cek pesanan, lalu buka DevTools Console:

```js
mrsExportApiLog()
```

Command tersebut akan download JSON berisi request dan response API terakhir. Untuk mengosongkan log:

```js
mrsClearApiLog()
```

## Web PWA Remote

```bash
npm run web:dev
```

Web app ada di `web/`. Fiturnya mobile-first, installable sebagai PWA, input manual 8-10 digit, Web NFC jika browser mendukung, pilihan cek pesanan/tap in, pilihan loket, dan WebSocket gateway. Web NFC butuh Android Chrome pada secure context (`https://` atau localhost).

Default gateway mengikuti host saat ini: `/ws`. Ubah dari tombol **Server** di web app, misalnya `wss://mers.endrisusanto.my.id/ws`.

### Docker Web PWA

```bash
docker compose up -d --build
```

Web app akan berjalan di:

```text
http://localhost:7465
```

## Android APK

```bash
npm run android:debug
```

APK debug dibuat di:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

Aplikasi Android native Kotlin ini membungkus `https://mers.endrisusanto.my.id` dalam WebView dan membaca NFC native Android. Hasil tap NFC otomatis mengisi input ID di web app; input manual tetap tersedia.

## 📦 Release

```bash
./release.sh
```

Script akan bump patch version, commit, tag `vX.Y.Z`, lalu push ke GitHub. GitHub Actions otomatis build NSIS `.exe`, zip extension, upload artifact, dan membuat GitHub Release.

## 🛡️ Keamanan & Privasi

Extension ini hanya berjalan pada domain yang ditentukan dalam `manifest.json` (terkait sistem MRS). Data ID yang Anda simpan/pin disimpan secara lokal di browser Anda menggunakan `chrome.storage.local` dan tidak dikirim ke server luar manapun.

---

Dibuat dengan ❤️ untuk efisiensi kerja.
Copyright © 2026 - [Endri Susanto](https://github.com/endrisusanto)
