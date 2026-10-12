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
        │       IME → AccessibilityService → Clipboard
        │  6. HistoryStore (opsional, di perangkat saja)
        ▼
UiState (phase, stage, partial, finalText, engine, insertChannel, error)
        │
        └──► dibaca oleh layar, bar IME, bubble, dan notifikasi
```

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
        │
        ▼
AssistantBrain.understand(text, settings, history)
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
AssistantExecutor.execute(action, settings)      AssistantSession
        │  Intent resmi Android:                  · TTS jawaban
        │  open_app  : MAIN/LAUNCHER + AppResolver · riwayat percakapan
        │  call/dial : ACTION_DIAL / ACTION_CALL   · status untuk UI
        │  alarm     : AlarmClock.ACTION_SET_ALARM
        │  timer     : AlarmClock.ACTION_SET_TIMER
        │  message   : wa.me → smsto: → ACTION_SEND
        │  torch     : CameraManager.setTorchMode
        │  volume    : AudioManager (absolut/relatif)
        │  settings  : Settings.ACTION_* per panel
        │  note      : HistoryStore.appendNote
        ▼
Outcome(success, message) → reply final → dibacakan + dikirim ke UI
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

4. **Kata kunci panggilan memakai recognizer, bukan model KWS khusus.**
   Jujur soal trade-off: ini hemat pengembangan tetapi lebih boros baterai.
   Karena itu fitur ini **mati secara bawaan** dan diberi peringatan di Pengaturan.
   Titik ganti ke model khusus hanyalah `WakeWordListener` — seluruh kode lain
   hanya bergantung pada callback `onWake`.

---

## 4. Penempelan teks: tiga jalur, satu API

`TextInserter` mencoba jalur sesuai `InsertMethod` (bawaan: `AUTO`):

| Urutan | Jalur | Syarat | Hasil |
| --- | --- | --- | --- |
| 1 | `RetrobitImeService` → `InputConnection.commitText()` | Keyboard Retrobit aktif | Teks masuk tepat di kursor |
| 2 | `TextInsertAccessibilityService` | Layanan aksesibilitas diaktifkan | Salin ke klip lalu `ACTION_PASTE` ke kolom fokus |
| 3 | Papan klip | Selalu | Teks siap ditempel manual + pemberitahuan |

Layanan aksesibilitas sengaja **tidak membaca isi layar**: `onAccessibilityEvent` kosong,
tidak ada paket yang dimonitor (`android:accessibilityEventTypes` hanya fokus & jendela,
tanpa pengumpulan teks masukan). Aplikasi hanya bertindak saat dipanggil.

---

## 5. Data & keamanan

| Data | Lokasi | Catatan |
| --- | --- | --- |
| Preferensi | `DataStore` (`retrobit_settings`) | Semua opsi mesin, bahasa, pemicu |
| API key | DataStore, **terenkripsi AES-GCM** dengan kunci di Android Keystore | Kalau Keystore tidak tersedia (mis. unit test) → ditandai `plain:` dan UI memperingatkan |
| Riwayat dikte & percakapan | JSONL di penyimpanan internal aplikasi | Maksimal 200 entri, bisa dihapus/dimatikan |
| Audio rekaman | `cacheDir/rekaman/*.wav` | Dihapus setelah transkripsi (berhasil maupun gagal) |

Tidak ada telemetri, tidak ada server milik proyek. Setiap byte yang keluar dari perangkat
hanya menuju penyedia yang API key-nya dimasukkan pengguna sendiri.

---

## 6. Peta berkas

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
| `ui/AppViewModel.kt` | Satu sumber status untuk seluruh layar |

---

## 7. Cara memperluas

| Ingin menambah… | Sentuh berkas |
| --- | --- |
| Mesin STT baru (mis. Azure, Whisper lokal) | Buat kelas `SttEngine`, tambahkan satu cabang di `HybridSttEngine` |
| Perintah asisten baru | `CommandParser` (offline) + `Prompts`/`IntentMapper` + `AssistantExecutor` |
| Aksi berbeda di UI | `DictationController.UiState` (semua permukaan otomatis ikut) |
| Penyedia LLM gaya OpenAI lain | Cukup isi base URL & model di Pengaturan — tidak perlu kode |
| Model wake word khusus | Ganti isi `WakeWordListener` (kontraknya satu callback) |
