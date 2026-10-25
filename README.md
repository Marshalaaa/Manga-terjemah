# Manga Terjemah — terjemahan LIVE Jepang → Indonesia (Android)

Baca manga di Chrome seperti biasa. Begitu kamu berhenti scroll sebentar (±½ detik),
teks Jepang di layar otomatis ditimpa kotak terjemahan bahasa Indonesia. Saat kamu scroll
lagi, kotaknya hilang dan akan muncul lagi di posisi baru ketika layar diam.
Semua berjalan **offline di HP** (Google ML Kit), gratis, tanpa API key.

## Bahasa Korea (Weverse, webtoon, media sosial)

Di layar utama pilih **Korea – Weverse, webtoon, media sosial**. Pilihan ini bisa diganti kapan saja,
juga saat terjemahan live sedang berjalan.
- **Mode Gemini (disarankan):** postingan artis, komentar, balasan, dan chat live dibaca per item,
  lalu diterjemahkan dengan gaya bahasa fans Indonesia. Nama, sapaan (oppa, unnie, hyung) dan emoji
  dipertahankan; ㅋㅋㅋ jadi "wkwk". Tombol, jumlah like, waktu posting, dan terjemahan bawaan Weverse dilewati.
- **Mode offline:** ML Kit Korea. Tekan **Unduh model** lagi setelah memilih Korea (model terpisah dari Jepang).
- Jalan di aplikasi Weverse maupun weverse.io di Chrome, karena yang dibaca adalah layar.

## Mode online: Gemini (disarankan)

Mode ini jauh lebih akurat, terutama untuk teks vertikal dan bahasa gaul.
1. Buka Google AI Studio (tombol ada di aplikasi), login, pilih *Create API key*.
2. Tempel key di aplikasi, lalu tekan **Tes API key**.
3. Model default `gemini-3.5-flash-lite` memakai tier gratis yang punya kuota harian.
   Kalau kuota habis (HTTP 429), aplikasi otomatis memakai mode offline selama 10 menit lalu mencoba lagi.
4. Layar yang sama tidak dikirim ulang (ada cache), jadi kuota lebih hemat.
Catatan: data tier gratis boleh dipakai Google untuk meningkatkan layanannya.

## Cara kerja singkat

1. Layar direkam terus (izin rekam layar Android).
2. Tiap frame diringkas jadi "sidik jari" kecil 24×48 kotak untuk mendeteksi scroll.
3. Kalau layar bergerak, kotak terjemahan disembunyikan.
4. Kalau layar diam 350 ms, frame dibaca (OCR Jepang), diterjemahkan, lalu kotak ditempel.
5. Terjemahan disimpan di cache, jadi saat scroll balik hasilnya langsung muncul.

Tombol bulat **訳** dipakai untuk jeda atau lanjut (abu-abu berarti dijeda), dan bisa digeser ke mana saja.

## Cara mendapatkan APK

1. Buat repository baru di github.com.
2. Upload **seluruh isi folder ini**, termasuk folder `.github`.
3. Buka tab **Actions** dan tunggu workflow **Build APK** selesai (±5–8 menit).
4. Klik run yang sudah selesai → **Artifacts** → unduh `MangaTerjemah-apk`.
5. Ekstrak, pindahkan `app-debug.apk` ke HP, lalu install (izinkan "sumber tidak dikenal").

Alternatif: buka folder ini di **Android Studio** → *Build → Build APK(s)*.

## Cara pakai

1. Buka **Manga Terjemah**.
2. Izinkan *tampil di atas aplikasi lain*.
3. Tekan **Unduh model** sekali saja (±30 MB, pakai Wi-Fi).
4. Tekan **Mulai terjemahan live**. Di dialog Android, pilih **Bagikan seluruh layar** → Mulai.
5. Buka Chrome dan baca. Untuk berhenti, pakai tombol *Berhenti* di notifikasi.

## Keterbatasan yang perlu diketahui

- **Kotak terjemahan 80% pekat.** Android 12+ memblokir sentuhan yang menembus overlay yang
  lebih pekat dari itu, jadi teks Jepang di bawahnya masih samar terlihat. Ini memang syarat
  supaya kamu tetap bisa scroll.
- Android 14+ meminta izin rekam layar setiap kali terjemahan dimulai. Itu aturan sistem.
- OCR offline kadang salah membaca teks vertikal yang rapat atau tulisan bergaya (efek suara).
- Iklan/GIF besar yang terus bergerak bisa membuat terjemahan sering muncul-hilang.
  Ketuk 訳 untuk jeda kalau mengganggu.
- Kalau layar diputar, hentikan lalu mulai ulang.
- Rekam layar & OCR terus-menerus memakai baterai lebih banyak dari biasa.

## Struktur kode

```
app/src/main/java/id/manga/terjemah/
├── MainActivity.kt           layar pengaturan, izin, unduh model
├── TranslatorService.kt      rekam layar + logika live (deteksi scroll/diam) + tombol 訳
├── FrameSignature.kt         sidik jari frame untuk deteksi gerak
├── OfflineEngine.kt          ML Kit: OCR Jepang + terjemahan on-device + cache
├── TranslationOverlayView.kt menggambar kotak terjemahan (tembus sentuhan)
└── TranslationEngine.kt      model data
```

Parameter yang bisa disetel ada di bagian atas `TranslatorService.kt`:
`SETTLE_MS` (lama diam sebelum menerjemahkan), `MOVE_THRESHOLD`, `SETTLE_THRESHOLD`.
