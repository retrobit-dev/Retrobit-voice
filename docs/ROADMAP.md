# Rencana pengembangan (roadmap)

Rilis 0.1.0 adalah fondasi yang sudah **berfungsi penuh**: dikte hybrid, penempelan tiga jalur,
asisten perintah offline + LLM, bubble, tile, dan integrasi asisten bawaan.

Di bawah ini urutan prioritas berikutnya. Setiap item ditulis dengan **berkas yang perlu disentuh**,
supaya bisa langsung dikerjakan.

---

## 0.2 — Keandalan di lapangan

| Item | Kenapa | Sentuh |
| --- | --- | --- |
| Rekam riwayat transkripsi + tombol "coba mesin lain" di layar hasil | Kalau on-device salah dengar, pengguna bisa mengulang ke cloud tanpa bicara ulang | `DictationController` (simpan berkas sementara opsional), `DictationScreen` |
| Kamus pengguna (nama & istilah sendiri) | Nama orang/istilah teknis adalah sumber kesalahan utama STT | `LocalTextCleaner.Options.replacements` sudah ada → tinggal UI di Pengaturan + penyimpanan |
| Mode "dikte berkelanjutan" (lanjut merekam setelah jeda) | Untuk menulis panjang tanpa menekan tombol berulang | `PcmRecorder` (batas durasi), `DictationController` |
| Umpan balik haptic + suara mulai/berhenti | Kejelasan saat dikte tanpa melihat layar | `DictationService`, `OverlayBubbleService` |
| Cek bahasa on-device di Diagnostik | Menghindari kebingungan "kenapa on-device selalu gagal" | `UiState`/`HybridSttEngine.statusSummary()` diperluas |
| Tangkapan layar otomatis untuk README | Tema baru ini butuh bukti visual di halaman repo | Roborazzi/Paparazzi atau `screengrab` + jalankan di CI |
| Uji kontras WCAG untuk mode PAPER | Kertas terang gampang membuat teks abu-abu terlalu pudar | `ui/theme/Theme.kt` (nilai `dimLight`/`faintLight`) |

## 0.3 — Asisten lebih pintar, tetap sopan privasi

| Item | Kenapa | Sentuh |
| --- | --- | --- |
| Perintah berantai ("buka Maps lalu cari SPBU terdekat") | Mendekati kualitas asisten besar | `CommandParser` (pemisah klausa) + `AssistantBrain` |
| Konteks layar (aplikasi apa yang terbuka) untuk perintah ambigu | "Balas chat ini" butuh tahu konteks | `AssistantExecutor` + `UsageStatsManager` (izin opsional, didokumentasikan) |
| Aksesori: baca notifikasi, jawab WhatsApp otomatis (dengan konfirmasi) | Fitur yang paling sering diminta pengguna Android | `TextInsertAccessibilityService` diperluas; wajib ada konfirmasi eksplisit |
| Percakapan multi-giliran dengan memori lebih panjang | "Kirim ke yang tadi" | `AssistantSession` (`turns` → ringkasan berkala) |
| Suara TTS yang bisa dipilih + kecepatan | Kenyamanan | `TtsSpeaker` (setVoice/setSpeechRate) |

## 0.4 — Alternatif mesin (tanpa API key)

| Item | Kenapa | Sentuh |
| --- | --- | --- |
| Dukungan **whisper.cpp** (model kecil, offline) via NDK | Akurasi jauh lebih baik dari on-device bawaan, tetap 100% offline | Modul baru `:whisper`, `SttEngine` baru, JNI |
| Dukungan **Vosk** (model Indonesia kecil) | Ringan untuk perangkat kelas bawah | Sama seperti di atas, unduhan model dari dalam aplikasi |
| Model **wake word khusus** (openWakeWord / Porcupine) | Hemat baterai dibanding recognizer | Ganti isi `WakeWordListener` |
| LLM lokal (llama.cpp / Gemini Nano) | Asisten pintar tanpa internet | `LlmProvider` baru di `LlmClient` |

## 1.0 — Rilis Play Store

- [ ] Tanda tangan rilis stabil (keystore di GitHub Secrets — CI sudah membacanya)
- [ ] Ikon adaptif & grafik store yang disempurnakan
- [ ] Onboarding bergambar (foto: menyalin di WhatsApp, dikte email)
- [ ] Uji di minimal 8 perangkat nyata (Pixel, Samsung, Xiaomi, Oppo, vivo, Realme — khususnya perilaku FGS & IME tiap OEM)
- [ ] Uji aksesibilitas (TalkBack) & kontras warna
- [ ] Kebijakan privasi publik + formulir Data safety Play Console
- [ ] Beta tertutup 20 pengguna → perbaikan → terbuka

---

## Utang teknis yang diketahui (jujur)

| Utang | Dampak | Rencana |
| --- | --- | --- |
| `WakeWordListener` memakai recognizer ulang-alik | Boros baterai kalau dibiarkan menyala | Ganti ke model KWS (0.4) |
| Belum ada uji instrumentasi (Espresso/Compose test) | Regresi UI bisa lolos | Tambah `androidTest` di 0.2 |
| `AssistantSession.currentSettings()` memakai `runBlocking` | Berpotensi memblokir thread pemanggil | Ubah ke `suspend` saat layar UI ditata ulang |
| Belum ada dukungan RTL penuh untuk teks Arab | Kosmetik | Uji & perbaiki di 0.2 |
| Audio hanya WAV 16 kHz mono | Tidak bisa dikirim ke layanan yang meminta opus/mp3 | Tambah encoder kalau dibutuhkan |
| `RecognitionService` masih stub | Pihak ketiga belum bisa memakai Retrobit sebagai recognizer | Implementasi penuh di 0.4 kalau ada permintaan |
