# Manga Terjemah — terjemahan LIVE Jepang → Indonesia (Android)

Baca manga di Chrome seperti biasa. Begitu kamu berhenti scroll sebentar (±½ detik),
teks Jepang di layar otomatis ditimpa kotak terjemahan bahasa Indonesia. Saat kamu scroll
lagi, kotaknya hilang dan akan muncul lagi di posisi baru ketika layar diam.
Semua berjalan **offline di HP** (Google ML Kit), gratis, tanpa API key.

## Kamus offline (biar terjemahan tidak kaku)

Sebelum diterjemahkan ML Kit, isi tiap balon dicek dulu ke kamus, dengan urutan:
1. **Koreksi buatanmu.** Tekan lama tombol **訳** untuk membetulkan terjemahan yang sedang tampil.
   Kalau kalimat yang sama muncul lagi, terjemahanmu yang dipakai.
2. **Frasa manga** (`app/src/main/assets/frasa_manga.tsv`), sekitar 290 ungkapan pendek yang sering
   muncul (なんだと！？, くそっ, ちょっと待って…). Bebas diubah.
3. **Kalimat Tatoeba** Jepang–Indonesia, diunduh otomatis oleh GitHub Actions setiap build
   (`scripts/buat_kamus_tatoeba.py`). Sumber: [Tatoeba](https://tatoeba.org), lisensi CC BY 2.0 FR.

Kalau tidak ada yang cocok, baru ML Kit dipakai. Sebelumnya nama-nama dari **Daftar nama**
(misalnya `ヒカリ = Hikari`) dikunci dulu supaya tidak diterjemahkan jadi kata biasa.

Pencocokan dilakukan per balon utuh. Tanda baca, spasi, ー dobel, dan っ di akhir diabaikan,
jadi 「くそっ！」 dan 「くそ」 dianggap sama.

## Cara kerja singkat

1. Layar direkam terus (izin rekam layar Android).
2. Tiap frame diringkas jadi "sidik jari" kecil 24×48 kotak untuk mendeteksi scroll.
3. Kalau layar bergerak, kotak terjemahan disembunyikan.
4. Kalau layar diam 350 ms, frame dibaca (OCR Jepang), dicek ke kamus atau diterjemahkan ML Kit, lalu kotak ditempel.
5. Terjemahan disimpan di cache, jadi saat scroll balik hasilnya langsung muncul.

Tombol bulat **訳**: ketuk untuk jeda/lanjut (abu-abu berarti dijeda), tekan lama untuk koreksi, geser untuk memindahkan.

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
├── Kamus.kt                  koreksi, frasa manga, Tatoeba, daftar nama
├── KoreksiActivity.kt        layar koreksi (tekan lama 訳)
├── TranslationOverlayView.kt menggambar kotak terjemahan (tembus sentuhan)
└── TranslationEngine.kt      model data
```

Parameter yang bisa disetel ada di bagian atas `TranslatorService.kt`:
`SETTLE_MS` (lama diam sebelum menerjemahkan), `MOVE_THRESHOLD`, `SETTLE_THRESHOLD`.
