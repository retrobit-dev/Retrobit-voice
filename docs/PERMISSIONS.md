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
| `QUERY_ALL_PACKAGES` | **Hanya** untuk mencari aplikasi yang dimaksud pengguna ("buka WhatsApp") pada Android 11+; tidak ada pemindaian di latar belakang |

## Integrasi khusus (dipilih pengguna di Pengaturan sistem)

| Integrasi | Cara mengaktifkan | Apa yang dilakukan aplikasi |
| --- | --- | --- |
| **Input Method (IME)** | Pengaturan → Sistem → Bahasa & masukan → Keyboard → aktifkan "Retrobit Voice (Dikte)", lalu pilih sebagai keyboard aktif | Menyediakan bar mikrofon; teks hasil dikte dikirim via `InputConnection.commitText()` ke kolom yang sedang difokuskan |
| **AccessibilityService** | Pengaturan → Aksesibilitas → "Retrobit Voice — tempel otomatis" | Hanya bertindak saat dipanggil: menyalin teks ke klip lalu mengirim `ACTION_PASTE`/`ACTION_SET_TEXT` ke node yang difokuskan. Tidak ada pemantauan layar serta tidak ada pengumpulan data |
| **VoiceInteractionService** (asisten bawaan) | Pengaturan → Aplikasi → Asisten digital → Retrobit Voice | Membuka layar asisten dan langsung mendengarkan saat tombol Home ditekan lama |
| **System Alert Window** (bubble) | Pengaturan → Aplikasi → Retrobit → Tampil di atas aplikasi lain | Menampilkan satu bubble melayang yang bisa digeser |
| **RecognitionService** | Tidak pernah dipilih otomatis — tidak punya `intent-filter` agar tidak muncul di pemilih recognizer sistem | Diperlukan sebagai referensi `recognitionService` pada `VoiceInteractionService`; implementasinya minimal dan melaporkan error yang jelas |
| **Quick Settings Tile** | Ditarik dari panel Pengaturan Cepat | Sekali tekan → asisten mendengarkan |
| **Pengecualian optimasi baterai** | Tindakan pengguna terhadap aplikasi (opsional) | Agar rekaman & kata kunci tidak terputus saat layar mati |

## Apa yang **tidak** dilakukan aplikasi ini

- ❌ Tidak ada analytics, crash reporting pihak ketiga, atau iklan.
- ❌ Tidak ada pengiriman audio/teks ke server proyek ini (server semacam itu tidak ada).
- ❌ Tidak membaca isi layar, kata sandi, atau pesan masuk lewat layanan aksesibilitas.
- ❌ Tidak memulai panggilan atau mengirim pesan tanpa membuka aplikasi terkait.
- ❌ Tidak berjalan di latar belakang tanpa jejak: setiap perekaman memunculkan notifikasi.

## Kalau ingin memeriksa sendiri

1. **Log aktivitas:** tab Pengaturan → Diagnostik → *Perbarui*. Berisi jejak mesin mana yang dipakai.
2. **Papan klip:** jalur penempelan cadangan selalu terlihat di layar Dikte ("Ditempel lewat: papan klip").
3. **Jaringan:** nyalakan mode pesawat, pilih mesin "Di perangkat" — dikte tetap jalan, membuktikan tidak ada panggilan jaringan.
4. **Riwayat:** matikan "Simpan riwayat di perangkat" lalu periksa bahwa berkas `dictation_history.jsonl` tidak bertambah.
