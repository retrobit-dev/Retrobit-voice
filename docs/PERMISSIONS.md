# Peta izin & integrasi sistem

Semua yang diminta aplikasi ini, alasannya, dan apa yang terjadi kalau pengguna menolak.
Tabel ini juga berguna saat mengisi Play Console (Data safety) atau menjelaskan ke pengguna.

## Izin runtime

| Izin | Dipakai untuk | Kalau ditolak |
| --- | --- | --- |
| `RECORD_AUDIO` | Merekam ucapan untuk dikte & asisten | Fitur inti mati; UI menampilkan tombol "Izinkan" di Beranda |
| `POST_NOTIFICATIONS` (API 33+) | Notifikasi status "sedang mendengarkan" | Dikte tetap jalan, hanya tanpa notifikasi |
| `READ_CONTACTS` (opsional) | Menemukan nomor untuk "telepon Ibu" / "kirim pesan ke Budi" | Pengguna harus menyebutkan nomor langsung; tidak ada fitur lain yang terpengaruh |
| `CALL_PHONE` (opsional) | Menekan panggil otomatis dari perintah | Tanpa izin ini, aplikasi membuka dialer dan pengguna menekan tombol panggil sendiri (**perilaku bawaan & disarankan**) |
| `SET_ALARM` (`com.android.alarm.permission.SET_ALARM`) | Membuat alarm/timer lewat aplikasi jam bawaan | Intent alarm gagal, aplikasi memberi pesan jelas |

## Izin normal / instan

| Izin | Alasan |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Hanya untuk STT cloud & LLM yang pengguna aktifkan sendiri (BYOK) |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE` | Merekam secara sah dari IME/bubble di Android 11+ |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Bubble melayang (tidak ada kategori FGS yang lebih tepat) |
| `SYSTEM_ALERT_WINDOW` | Bubble mikrofon melayang (opsional) |
| `VIBRATE` | Umpan balik saat mulai/berhenti merekam |

> **Catatan package visibility:** aplikasi ini **tidak** meminta `QUERY_ALL_PACKAGES`.
> Untuk perintah seperti "buka WhatsApp", deklarasi terarah di blok `<queries>`
> (intent `MAIN`/`LAUNCHER`) sudah cukup — dan kebijakan Play memang mewajibkan
> pendekatan terarah itu. Kalau suatu saat ada aplikasi yang tidak terdeteksi,
> tambahkan `<package android:name="…"/>` spesifik, bukan izin broad visibility.

## Integrasi khusus (dipilih pengguna di Pengaturan sistem)

| Integrasi | Cara mengaktifkan | Apa yang dilakukan aplikasi |
| --- | --- | --- |
| **Input Method (IME)** | Pengaturan → Sistem → Bahasa & masukan → Keyboard → aktifkan "Retrobit Voice (Dikte)", lalu pilih sebagai keyboard aktif | Menyediakan bar mikrofon; teks hasil dikte dikirim via `InputConnection.commitText()` ke kolom yang sedang difokuskan |
| **AccessibilityService** | Pengaturan → Aksesibilitas → "Retrobit Voice — sisip otomatis" | Mendeteksi kolom teks fokus, menyisipkan langsung melalui `ACTION_SET_TEXT`/pemulihan caret, dan membaca tombol volume bila diaktifkan. Snapshot teks layar hanya tersedia dengan opt-in terpisah dan permintaan kontekstual; password serta kolom edit dikecualikan |
| **VoiceInteractionService** (asisten bawaan) | Pengaturan → Aplikasi → Asisten digital → Retrobit Voice | Membuka layar asisten dan langsung mendengarkan saat tombol Home ditekan lama |
| **System Alert Window** (bubble) | Pengaturan → Aplikasi → Retrobit → Tampil di atas aplikasi lain | Menampilkan bubble yang bisa digeser, hanya saat kolom teks aktif; gelombang menampilkan level mic saat dikte |
| **RecognitionService** | Tidak pernah dipilih otomatis — tidak punya `intent-filter` agar tidak muncul di pemilih recognizer sistem | Diperlukan sebagai referensi `recognitionService` pada `VoiceInteractionService`; implementasinya minimal dan melaporkan error yang jelas |
| **Quick Settings Tile** | Ditarik dari panel Pengaturan Cepat | Sekali tekan → asisten mendengarkan |
| **Pengecualian optimasi baterai** | Tindakan pengguna terhadap aplikasi (opsional) | Agar rekaman & kata kunci tidak terputus saat layar mati |

### Dua setelan yang perlu diketahui

- **Pintasan tombol volume** dapat aktif bersamaan: tahan Volume Atas sekitar 0,7 detik untuk asisten/voice command; tahan Volume Bawah untuk mulai dikte dan lepas untuk selesai. Ketukan singkat tetap mengatur volume. Masing-masing pintasan memerlukan layanan aksesibilitas dan dapat diaktifkan terpisah di Pengaturan.
- **Wake word kontekstual** memilih mode dari fokus saat frasa terdeteksi: kolom teks yang sedang fokus berarti mulai dikte; di luar mode mengetik berarti dengarkan perintah asisten.
- **Konteks layar** mati secara bawaan. Dengan opt-in aktif, snapshot sementara dibuat saat sesi asisten dimulai dan hanya dikirim ke LLM yang dipilih bila perintah secara eksplisit meminta membaca/membalas teks layar. Snapshot tidak ditulis ke riwayat; jika opt-in mati, layar tidak dibaca.

## Apa yang **tidak** dilakukan aplikasi ini

- ❌ Tidak ada analytics, crash reporting pihak ketiga, atau iklan.
- ❌ Tidak ada pengiriman audio/teks ke server proyek ini (server semacam itu tidak ada).
- ❌ Tidak memindai layar di latar belakang. Pembacaan snapshot hanya terjadi dengan opt-in dan perintah kontekstual; password dan kolom edit dikecualikan.
- ❌ Tidak mengirim pesan otomatis. Setelah persetujuan, aplikasi tujuan dibuka dengan draf yang perlu ditinjau dan dikirim oleh pengguna.
- ❌ Tidak berjalan di latar belakang tanpa jejak: setiap perekaman memunculkan notifikasi.

## Kalau ingin memeriksa sendiri

1. **Log aktivitas:** tab Pengaturan → Diagnostik → *Perbarui*. Berisi jejak mesin mana yang dipakai.
2. **Penyisipan langsung:** layar Dikte menampilkan jalur IME/aksesibilitas yang berhasil. Tombol Salin adalah tindakan terpisah yang sengaja dipilih pengguna; tidak ada fallback clipboard otomatis.
3. **Jaringan:** nyalakan mode pesawat, pilih mesin "Di perangkat" — dikte tetap jalan, membuktikan tidak ada panggilan jaringan.
4. **Riwayat:** matikan "Simpan riwayat di perangkat" lalu periksa bahwa berkas `dictation_history.jsonl` tidak bertambah.
