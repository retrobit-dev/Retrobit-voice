# Arsitektur Retrobit Voice

Dokumen ini menjelaskan **mengapa** kode disusun seperti ini, bukan hanya apa isinya.
Bacalah bagian "Keputusan desain" dulu kalau Anda ingin mengubah perilaku aplikasi.

---

## 1. Gambaran lapisan

```
                    ┌─────────────────────────── UI (Compose) ───────────────────────────┐
                    │ Beranda · Dikte · Asisten · Pengaturan · Panduan                   │
                    └───────────────┬────────────────────────────────────────────────────┘
                                    │  AppViewModel (satu sumber status)
┌───────────────────────────────────▼────────────────────────────────────────────────────┐
│                                   AppGraph (service locator)                            │
└───┬───────────────┬───────────────────┬───────────────────┬────────────────┬───────────┘
    │               │                   │                   │                │
┌───▼─────┐  ┌──────▼──────┐  ┌─────────▼────────┐  ┌───────▼──────┐  ┌──────▼──────┐
│Dictation│  │ Assistant   │  │   STT layer      │  │  AI layer    │  │ Data layer  │
│Controller│ │ Brain+Exec  │  │ Hybrid→{on-dev,  │  │ LlmClient    │  │ Settings    │
│         │  │+Session+TTS │  │ cloud, system}   │  │ Prompts      │  │ History     │
└───┬─────┘  └──────┬──────┘  └─────────┬────────┘  └──────────────┘  │ SecretStore │
    │               │                   │                             └─────────────┘
┌───▼───────────────▼───────────────────▼────────────────────────────────────────────────┐
│                        Android platform surfaces (pintu masuk pengguna)                 │
│  IME · AccessibilityService · Overlay bubble · Foreground service · QS tile ·          │
│  VoiceInteractionService · Activity (ASSIST / VOICE_COMMAND)                            │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

Aturan penting: **semua pintu masuk mengalir ke `DictationController` dan `AssistantSession`**.
Tidak ada logika transkripsi yang ditulis ulang di dalam IME atau bubble. Efeknya:
memperbaiki bug di satu tempat memperbaiki semua permukaan sekaligus, dan setiap permukaan
cukup berurusan dengan tampilan status.

---

## 2. Alur dikte (mode Wispr Flow)

```
pemicu (IME / bubble / tile / layar utama / notifikasi)
        │
        ▼
DictationService (foreground, FGS type = microphone)
        │  Android 11+ melarang akses mikrofon dari latar belakang;
        │  semua rekaman lewat service ini agar sah dan tidak dimatikan sistem.
        ▼
DictationController.start(source)
        │  1. cek izin RECORD_AUDIO
        │  2. PcmRecorder → WAV 16 kHz mono + VAD
        │     · VAD: noise floor adaptif, auto-stop saat hening N ms
        │  3. HybridSttEngine.transcribe(recording)
        │       AUTO  → on-device → cloud → SttAllFailedException
        │       (bisa dipaksa lewat Pengaturan)
        │  4. pembersihan teks: OFF / LOCAL / AI(LLM, fallback lokal)
        │  5. TextInserter.insert()
        │       IME.commitText() → AccessibilityService.ACTION_SET_TEXT + restore caret
        │       (tanpa fallback clipboard; jalur manual hanya menampilkan hasil)
        │  6. HistoryStore (opsional, di perangkat saja)
        ▼
UiState (phase, stage, partial, finalText, engine, insertChannel, error)
        │
        └──► dibaca oleh layar, bar IME, bubble, dan notifikasi
```

`OverlayBubbleService` menggabungkan status fokus dari aksesibilitas dengan status dikte: bubble disembunyikan di luar kolom teks yang fokus. Saat merekam, waveform kecil mengikuti amplitudo mikrofon; sumber `OVERLAY` auto-stop setelah hening walau pemicu global memakai mode tahan.

### Mengapa merekam sendiri, bukan menyerahkan mikrofon ke SpeechRecognizer?

Justru di sinilah inti fitur "hybrid". Dengan memiliki berkas WAV-nya, satu ucapan bisa
dicoba ke beberapa mesin tanpa meminta pengguna mengulang. Konsekuensinya kami harus
mengurus VAD sendiri — karena itu `VoiceActivityDetector` dibuat murni (tanpa Android)
dan diuji di JVM.

### Bagaimana on-device bisa membaca berkas?

Sejak **Android 13 (API 33)**, `RecognizerIntent.EXTRA_AUDIO_SOURCE` menerima
`ParcelFileDescriptor`. `AndroidOnDeviceSttEngine` memakai jalur itu. Di Android 12
(ketersediaan recognizer on-device, tetapi belum ada jalur berkas) aplikasi otomatis
turun ke cloud atau ke mode siaran langsung `AndroidSystemSttEngine`.

---

## 3. Alur asisten (mode Google Assistant)

```
kalimat pengguna (suara via DictationController.listenForText, atau teks)
        │   + snapshot layar satu-kali hanya bila opt-in aktif dan permintaan kontekstual
        ▼
AssistantBrain.understand(text, settings, history, screenContext)
        │
        ├─ 1. CommandParser (offline, regex + NumberWords)
        │       cocok & confidence ≥ 0.85 → langsung dieksekusi
        │
        ├─ 2. LlmClient.complete(systemPrompt = Prompts.assistant(...))
        │       LLM wajib menjawab JSON: {intent, slots, reply}
        │       IntentMapper → AssistantAction
        │
        └─ 3. Fallback → WebSearch + kalimat penjelas (tidak pernah buntu)
        │
        ▼
AssistantSession (menjaga konfirmasi)                    TTS
        │                                                   │
        ├─ send_message → pending card + prompt ya/tidak ──┤ (menunggu selesai)
        │   hanya affirmative eksplisit / tap konfirmasi     │
        ▼                                                   │
AssistantExecutor.execute(action, settings)                 │
        │  Intent resmi Android:                            │
        │  open_app  : MAIN/LAUNCHER + AppResolver          │
        │  call/dial : ACTION_DIAL / ACTION_CALL            │
        │  alarm     : AlarmClock.ACTION_SET_ALARM           │
        │  timer     : AlarmClock.ACTION_SET_TIMER           │
        │  message   : buka draf wa.me / SMS; pengguna tap Send
        │  torch     : CameraManager.setTorchMode             │
        │  volume    : AudioManager (absolut/relatif)         │
        │  settings  : Settings.ACTION_* per panel            │
        │  note      : HistoryStore.appendNote                │
        ▼                                                   │
Outcome(success, message) → jawaban + status UI ◄───────────┘
```

### Keputusan desain yang penting

1. **LLM diminta membalas JSON, bukan bahasa bebas.**
   Bahasa bebas membuat eksekusi aksi berisiko salah tafsir. Dengan skema
   `{intent, slots, reply}` hasilnya dapat divalidasi, bisa ditolak dengan aman,
   dan mudah diuji. Kalau JSON tidak valid, seluruh balasan diperlakukan sebagai
   jawaban biasa (`answer`) — jadi tidak pernah ada crash karena format.

2. **Aturan lokal selalu dicoba lebih dulu.**
   Perintah umum ("buka WhatsApp") dijalankan dalam milidetik, gratis, dan bekerja
   di pesawat terbang. LLM hanya dipakai ketika aturan gagal — ini juga menekan
   biaya API key pengguna.

3. **Aksi memakai Intent resmi, bukan akses paksa.**
   Kami tidak memanggil nomor secara diam-diam (defaultnya `ACTION_DIAL`, pengguna
   menekan tombol panggil) dan tidak mengirim pesan tanpa membuka aplikasi pesan.
   Ini konsisten dengan kebijakan Play Store dan menghindari aplikasi "menyamar".

4. **Pemantauan wake word memakai KWS personal sepenuhnya lokal.**
   Model Google speech-embedding menghasilkan fitur suara, lalu DTW mencocokkannya
   dengan tiga contoh yang direkam secara eksplisit oleh pengguna. `AudioRecord`
   tidak meminta fokus audio dan pemantauan pasif tidak membuka `SpeechRecognizer`;
   WAV enrolmen mentah dihapus, sementara fitur terenkripsi dengan Android Keystore
   dan dikecualikan dari backup. Deteksi dipersonalisasi untuk suara yang direkam
   dan perlu diulang saat frasa berubah. Saat terdeteksi, callback yang sama selalu
   membuka overlay/sesi asisten (bukan dikte berdasarkan fokus kolom); setelah itu
   alur STT perintah yang sudah ada tetap digunakan. Fokus audio eksklusif overlay
   tetap dipisahkan dari pemantauan pasif agar media dijeda hanya selama overlay.

---

## 4. Penyisipan teks: dua jalur langsung, tanpa clipboard

`TextInserter` mencoba jalur sesuai `InsertMethod` (bawaan: `AUTO`):

| Urutan | Jalur | Syarat | Hasil |
| --- | --- | --- | --- |
| 1 | `RetrobitImeService` → `InputConnection.commitText()` | Keyboard Retrobit aktif | Teks masuk tepat di kursor |
| 2 | `TextInsertAccessibilityService` → `ACTION_SET_TEXT` + `ACTION_SET_SELECTION` | Layanan aksesibilitas aktif dan kolom dapat diedit | Teks disisipkan pada seleksi/kursor dan posisi caret dipulihkan |

Jika jalur langsung gagal, status menjadi error; teks tidak disalin otomatis ke clipboard. Mode Dikte di aplikasi menampilkan hasil dan tombol Salin tetap merupakan tindakan manual pengguna.

Layanan aksesibilitas memakai event untuk mendeteksi fokus kolom dan tombol volume. Pembacaan isi jendela hanya dilakukan melalui snapshot terbatas satu-kali, bila opt-in konteks layar aktif dan pengguna meminta membaca/membalas layar. Snapshot mengecualikan node password/editable, tidak dicatat di riwayat, dan diperlakukan sebagai data tidak tepercaya oleh prompt LLM.

---

## 5. Data & keamanan

| Data | Lokasi | Catatan |
| --- | --- | --- |
| Preferensi | `DataStore` (`retrobit_settings`) | Semua opsi mesin, bahasa, pemicu |
| API key | DataStore, **terenkripsi AES-GCM** dengan kunci di Android Keystore | Kalau Keystore tidak tersedia (mis. unit test) → ditandai `plain:` dan UI memperingatkan |
| Riwayat dikte & percakapan | JSONL di penyimpanan internal aplikasi | Maksimal 200 entri, bisa dihapus/dimatikan; snapshot layar tidak dicatat |
| Snapshot layar | Memori sesi sementara | Opt-in mati secara bawaan; hanya diteruskan ke LLM untuk permintaan baca/balas layar, lalu dibuang |
| Audio rekaman | `cacheDir/rekaman/*.wav` | Dihapus setelah transkripsi (berhasil maupun gagal) |

Tidak ada telemetri, tidak ada server milik proyek. Setiap byte yang keluar dari perangkat
hanya menuju penyedia yang API key-nya dimasukkan pengguna sendiri.

---

## 6. Sistem visual: e-paper monokrom

Tema ada di `ui/theme/Theme.kt` dan seluruh komponennya di `ui/components/Common.kt`.
Keputusan yang perlu diketahui sebelum mengubah tampilan:

| Keputusan | Alasan |
| --- | --- |
| Palet hanya berisi tinta + kertas (8 nilai per mode) | E-paper tidak punya hue; menambahkan satu warna akan langsung merusak ilusi perangkat |
| Warna skema `error` = tinta, bukan merah | Kalau galat ditandai warna, info itu hilang di layar monokrom — jadi galat ditandai `!` + teks |
| Status dibawa glyph (`● ○ ■ □ ▚ ! > <`) | Satu-satunya pembeda yang tersisa setelah warna dihapus; sekaligus tetap terbaca oleh pengguna dengan buta warna |
| Inversi (tinta ↔ kertas) untuk keadaan aktif | Meniru perilaku panel 1-bit dan tetap kontras di kedua mode |
| Sudut 0–2dp, garis 1px, tipografi monospace | Bahasa visual instrumen/perangkat, bukan kartu konsumen |
| `Modifier.epaperTexture()` (drawBehind + garis 1px) | Tekstur murah: digambar saat draw, tanpa alokasi bitmap atau gambar berukuran besar |
| Dua mode permukaan lewat `RetrobitTheme(paperMode = ...)` | Kertas terang untuk pemakaian siang; panel gelap untuk malam — tanpa dua set kode |
| `PillsColumn` disusun manual (bukan `FlowRow`) | Menghindari API eksperimental agar proyek tetap bisa dibangun di versi Compose stabil mana pun |

Catatan: `MainActivity` tidak memakai `Scaffold`/`NavigationBar` Material, melainkan
`DeckNavBar` sendiri. Ini disengaja supaya bilah bawah tidak membawa serta elevasi,
lengkungan, dan warna Material — sekaligus menghindari ketergantungan pada
`NavigationBarItem` yang sulit dibuat monokrom.

### Edge-to-edge & inset (jangan dihapus)

Sejak `targetSdk = 35`, Android 15 **memaksa** tampilan edge-to-edge: jendela
menggambar di belakang status bar dan navigasi bar. Konsekuensinya, inset harus
ditangani sendiri — berbeda dengan komponen Material 3 yang menyuntiknya otomatis,
sedangkan `DeckNavBar` adalah `Row` biasa.

Penanganannya sengaja terkumpul di satu tempat, `MainActivity.onCreate()`:

```kotlin
enableEdgeToEdge()

Box(
    Modifier
        .fillMaxSize()
        .epaperTexture(textureColor)                        // latar: tetap penuh sampai tepi
        .windowInsetsPadding(WindowInsets.safeDrawing),     // isi: digeser masuk
) { MainScaffold(...) }
```

Urutan modifier-nya penting: tekstur digambar oleh modifier yang lebih luar,
sehingga latar tetap penuh sampai tepi layar sementara isinya masuk sebesar inset.
`WindowInsets.safeDrawing` dipilih (bukan `systemBars`) karena juga mencakup IME —
sejak edge-to-edge aktif, `windowSoftInputMode="adjustResize"` tidak lagi
menyusutkan jendela, jadi tanpa itu kolom isian tertutup keyboard.

Ikon bilah status/navigasi mengikuti mode tema lewat `isAppearanceLightStatusBars`
dan `isAppearanceLightNavigationBars` di `RetrobitTheme`. Opsi opt-out
(`windowOptOutEdgeToEdgeEnforcement`) sudah dimatikan di Android 16, jadi jangan
dijadikan jalan pintas.

## 7. Peta berkas

| Berkas | Tanggung jawab |
| --- | --- |
| `core/AppGraph.kt` | Service locator; satu-satunya tempat objek global dibuat |
| `core/Models.kt` | `Transcript`, `PcmRecording`, `AssistantAction`, `AssistantTurn` |
| `audio/PcmRecorder.kt` | `AudioRecord` → WAV + RMS untuk indikator |
| `audio/VoiceActivityDetector.kt` | Deteksi ucapan & hening (murni, teruji) |
| `stt/HybridSttEngine.kt` | Kaskade on-device → cloud, ringkasan kegagalan |
| `stt/AndroidOnDeviceSttEngine.kt` | Recognizer on-device + injeksi berkas (API 33+) |
| `stt/AndroidSystemSttEngine.kt` | Sesi siaran langsung dengan hasil parsial |
| `stt/CloudSttEngine.kt` | OpenAI/Groq (multipart) dan Deepgram (biner) |
| `ai/LlmClient.kt` | Chat completions + Gemini `generateContent` |
| `ai/Prompts.kt` | Prompt pembersihan & prompt JSON asisten |
| `assistant/CommandParser.kt` | Aturan offline (perintah Indonesia + Inggris) |
| `assistant/AppResolver.kt` | Pencocokan nama aplikasi (alias, Levenshtein) |
| `assistant/AssistantExecutor.kt` | Semua aksi Intent |
| `dictation/DictationController.kt` | Orkestrasi rekam → transkripsi → tempel |
| `dictation/insert/TextInserter.kt` | Tiga jalur penempelan |
| `text/LocalTextCleaner.kt` | Filler, tanda baca, kapitalisasi, kamus pengguna |
| `text/NumberWords.kt` | "tujuh" → 7 (id + en), durasi & jam |
| `ui/theme/Theme.kt` | Palet e-paper (Deck), glyph, tipografi mono, dua skema permukaan |
| `ui/components/Common.kt` | Seluruh komponen visual: Panel, TerminalHeader, LevelMeter, BracketButton, dll. |
| `ui/AppViewModel.kt` | Satu sumber status untuk seluruh layar |

---

## 8. Cara memperluas

| Ingin menambah… | Sentuh berkas |
| --- | --- |
| Mesin STT baru (mis. Azure, Whisper lokal) | Buat kelas `SttEngine`, tambahkan satu cabang di `HybridSttEngine` |
| Perintah asisten baru | `CommandParser` (offline) + `Prompts`/`IntentMapper` + `AssistantExecutor` |
| Aksi berbeda di UI | `DictationController.UiState` (semua permukaan otomatis ikut) |
| Penyedia LLM gaya OpenAI lain | Cukup isi base URL & model di Pengaturan — tidak perlu kode |
| Frontend/model KWS lokal | `WakeWordAudioFeatures`, `LocalWakeWordKws`, `WakeWordListener`; simpan profil lewat `WakeWordProfileStore` |
