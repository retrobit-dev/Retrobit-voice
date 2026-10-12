# Retrobit Voice

**Dikte secepat bicara + asisten suara Bahasa Indonesia — dua-duanya dalam satu aplikasi Android native.**

Retrobit Voice menggabungkan dua hal yang biasanya terpisah:

| Bagian | Meniru | Yang Anda dapat |
| --- | --- | --- |
| **Mode Dikte** | Wispr Flow | Ucapan langsung menjadi teks rapi di aplikasi apa pun — WhatsApp, Gmail, Notes, kolom pencarian |
| **Mode Asisten** | Google Assistant | "Buka WhatsApp", "telepon Ibu", "alarm jam 5 pagi", "timer 10 menit", "nyalakan senter", dan tanya-jawab bebas |

Dirancang **offline-first**: dikte dan puluhan perintah tetap jalan tanpa internet, lalu meningkat otomatis kalau Anda memasukkan API key milik Anda sendiri (BYOK).

---

## Fitur utama

### Dikte (Wispr Flow style)
- **Keyboard Retrobit** — IME khusus dengan tombol mikrofon; hasil bicara langsung masuk ke kolom yang aktif.
- **Bubble melayang** — tombol mikrofon selalu tersedia di atas aplikasi lain (ketuk = dikte, tekan lama = asisten).
- **Tempel otomatis lewat aksesibilitas** — teks menempel sendiri tanpa perlu membuka keyboard. Kalau tidak diaktifkan, teks otomatis disalin ke papan klip.
- **Mesin STT hybrid** — satu rekaman dicoba berurutan:
  1. **on-device** (Google recognizer, Android 12+, privat & gratis),
  2. **cloud** (OpenAI / Groq / Deepgram) kalau langkah pertama gagal,
  3. pesan diagnostik jelas kalau keduanya gagal.
- **Pembersihan teks dua tingkat** — aturan lokal (buang "eee/emm/anu", perbaiki tanda baca, huruf besar) atau LLM untuk hasil yang lebih natural.
- **Auto-stop saat berhenti bicara** — detektor aktivitas suara (VAD) dengan noise floor adaptif.
- Pemicu tile **Pengaturan Cepat** Android.

### Asisten (Google Assistant style)
- **Kaskade cerdas**: aturan lokal → LLM (opsional) → fallback pencarian web. Artinya asisten tetap berguna tanpa API key.
- Perintah yang dikenali secara offline: buka aplikasi, cari di web/YouTube/Maps/Tokopedia, telepon kontak atau nomor, alarm, timer, kirim pesan WhatsApp/SMS, catatan, senter, volume, dan buka panel pengaturan sistem.
- **Bisa dipilih sebagai asisten digital bawaan** (`VoiceInteractionService`) — tekan lama tombol Home langsung mendengarkan.
- **Kata kunci panggilan** opsional ("hai retro"), dengan catatan baterai yang jujur di pengaturan.
- Jawaban dibacakan dengan TTS bawaan Android.

### Privasi
- API key disimpan **terenkripsi** dengan Android Keystore.
- Audio **dihapus segera** setelah transkripsi.
- Riwayat hanya tersimpan di perangkat, bisa dimatikan sekali sentuh — tidak ada server milik proyek ini.
- Tidak ada analytics, tidak ada iklan, tidak ada pengiriman data diam-diam.

---

## Tangkapan layar alur kerja

```
        ┌────────────┐   tekan mikrofon   ┌──────────────┐   transkripsi   ┌──────────────┐
Anda ──►│ Keyboard / │ ─────────────────► │  Rekaman WAV │ ──────────────► │ Teks mentah  │
        │ Bubble     │                    │  16 kHz mono │  on-device →    │ (transkrip)  │
        └────────────┘                    └──────────────┘  cloud          └──────┬───────┘
                                                                                   │ pembersihan
                                            ┌──────────────┐   penempelan   ┌─────▼───────┐
                                            │ WhatsApp,    │ ◄──────────────│ Teks rapi   │
                                            │ Gmail, dll.  │  IME / a11y /  └─────────────┘
                                            └──────────────┘  clipboard
```

---

## Cara build

Butuh **Android Studio Ladybug (2024.2)** atau lebih baru, JDK 17, dan Android SDK 35.

```bash
git clone https://github.com/retrobit-dev/Retrobit-voice.git
cd Retrobit-voice
./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # uji parser & pembersih teks (JVM, tanpa emulator)
```

CI GitHub Actions (`.github/workflows/build.yml`) otomatis menjalankan unit test dan menghasilkan APK debug setiap push.
Tag `v*` juga membangun APK rilis dan melampirkannya ke GitHub Release.

> **Catatan sandbox pengembangan:** repositori ini bisa saja belum memuat `gradle/wrapper/gradle-wrapper.jar`.
> Kalau `./gradlew` menolak jalan, jalankan `gradle wrapper --gradle-version 8.9` sekali (atau buka proyek di
> Android Studio yang menyediakannya otomatis) — CI juga menangani hal ini sendiri.

---

## Aktivasi di perangkat (5 langkah)

1. **Pilih keyboard Retrobit** — Pengaturan → Sistem → Bahasa & masukan → Keyboard → aktifkan & pilih "Retrobit Voice (Dikte)".
   (Tombol keyboard di bar Retrobit membuka pemilih keyboard, bisa balik kapan saja.)
2. **Aktifkan layanan aksesibilitas** "Retrobit Voice — tempel otomatis" (menu Aksesibilitas). Ini yang membuat teks menempel sendiri.
3. **Izinkan bubble melayang** — "Tampil di atas aplikasi lain".
4. **Opsional: jadikan asisten bawaan** — Pengaturan → Aplikasi → Asisten digital → Retrobit Voice.
5. **Matikan optimasi baterai** untuk Retrobit supaya rekaman & kata kunci tidak terputus.

Panduan ini juga tersedia lengkap di dalam aplikasi (tab Beranda → *Buka panduan lengkap*) dengan tombol langsung ke tiap layar pengaturan.

---

## Konfigurasi mesin suara

| Mode | Kapan dipakai | Butuh |
| --- | --- | --- |
| Otomatis (hybrid) | Bawaan. On-device dulu, cloud sebagai cadangan | — |
| Di perangkat | Ingin 100% offline & privat | Android 12+ (transkripsi berkas: Android 13+) |
| Cloud | Butuh akurasi tertinggi | API key OpenAI / Groq / Deepgram |
| Bawaan Android | Mesin sistem langsung dengan hasil parsial instan | Google app terpasang |

Untuk asisten, pilih otak AI: **Nonaktif** (aturan lokal saja), **OpenAI**, **Gemini**, atau **Ollama** (server di komputer sendiri — tidak ada data yang keluar dari jaringan lokal Anda).

---

## Struktur proyek

```
app/src/main/java/dev/retrobit/voice/
├── audio/       Perekam PCM, detektor VAD, penulis WAV
├── ai/          Klien LLM (OpenAI-compatible + Gemini), prompt, HTTP
├── assistant/   Parser perintah offline, otak asisten, eksekutor aksi, TTS, wake word
├── core/        Model, log, service locator (AppGraph)
├── data/        Pengaturan (DataStore), enkripsi API key, riwayat
├── dictation/   Pengendali dikte, service mikrofon, IME, aksesibilitas, bubble
├── stt/         Mesin STT: on-device, cloud, sistem, dan orkestrator hybrid
├── text/        Pembersih teks lokal & pembaca angka
└── ui/          Compose: Beranda, Dikte, Asisten, Pengaturan, Panduan
```

Detail arsitektur & keputusan desain: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)
Peta izin sistem: [`docs/PERMISSIONS.md`](docs/PERMISSIONS.md)
Rencana pengembangan: [`docs/ROADMAP.md`](docs/ROADMAP.md)

---

## Pemeriksa konsistensi (tanpa Android SDK)

```bash
python3 tools/verify_project.py
```

Memeriksa referensi `R.string/R.drawable/R.id`, kelas di manifest, referensi resource di XML, keseimbangan kurung berkas Kotlin, dan cakupan terjemahan.

---

## Lisensi

MIT — lihat [LICENSE](LICENSE).
