package dev.retrobit.voice.dictation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.audio.PcmRecorder
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.CleanupMode
import dev.retrobit.voice.data.DictationTrigger
import dev.retrobit.voice.data.SttEngineChoice
import dev.retrobit.voice.text.LocalTextCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pengendali dikte — satu pintu masuk untuk semua pemicu:
 * keyboard Retrobit, bubble melayang, tile Pengaturan Cepat, layar utama,
 * dan sesi asisten.
 *
 * Alur: rekam → transkripsi (hybrid) → rapikan (lokal/LLM) → sisipkan langsung → riwayat.
 */
class DictationController(
    private val context: Context,
    private val graph: AppGraph,
) {

    enum class Phase { IDLE, LISTENING, TRANSCRIBING, CLEANING, INSERTING, DONE, ERROR }

    enum class Source { MANUAL, IME, OVERLAY, OVERLAY_PREVIEW, WAKE_WORD, TILE, ASSISTANT, VOLUME }

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val source: Source = Source.MANUAL,
        val stage: String = "",
        val partial: String = "",
        val rawText: String = "",
        val finalText: String = "",
        val engine: String = "",
        val error: String? = null,
        val needsMicrophonePermission: Boolean = false,
        val amplitude: Float = 0f,
        val elapsedMs: Long = 0L,
        val insertChannel: String? = null,
    ) {
        val isBusy: Boolean get() = phase == Phase.LISTENING || phase == Phase.TRANSCRIBING ||
            phase == Phase.CLEANING || phase == Phase.INSERTING
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val recorder = PcmRecorder()
    private var captureJob: Job? = null
    private var amplitudeJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val recordingsDir: File get() = File(context.cacheDir, "rekaman").apply { if (!exists()) mkdirs() }

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun reset() {
        if (_state.value.isBusy) return
        _state.value = UiState()
    }

    /** Mulai merekam. Mengembalikan false kalau izin mikrofon belum ada. */
    fun start(source: Source): Boolean {
        if (_state.value.isBusy) {
            VoiceLog.w("Dikte sedang berjalan, abaikan permintaan baru")
            return true
        }
        if (!hasMicrophonePermission()) {
            _state.value = UiState(
                phase = Phase.ERROR,
                source = source,
                needsMicrophonePermission = true,
                error = "Izin mikrofon belum diberikan",
            )
            return false
        }
        if (graph.speakerVerification.state.value.enrollmentActive) {
            _state.value = UiState(
                phase = Phase.ERROR,
                source = source,
                error = context.getString(R.string.dictation_speaker_enrollment_active),
            )
            return false
        }
        if (graph.wakeWordEnrollment.state.value.enrollmentActive) {
            _state.value = UiState(
                phase = Phase.ERROR,
                source = source,
                error = context.getString(R.string.dictation_wake_word_enrollment_active),
            )
            return false
        }

        _state.value = UiState(phase = Phase.LISTENING, source = source, stage = "Mendengarkan…")

        captureJob = scope.launch {
            var wakeWordPaused = false
            try {
                // Lepas AudioRecord KWS pasif sebelum perekam dikte mengambil mikrofon.
                // Menunggu cleanup selesai mencegah dua klien audio berjalan bersamaan.
                wakeWordPaused = true
                graph.assistant.pauseWakeWordForDictation()
                val settings = graph.settings.current()
                if (settings.speakerVerificationEnabled) {
                    // Speaker check must see the original WAV before any recognizer or cloud STT.
                    // SYSTEM_LIVE cannot expose its microphone audio to the local verifier, so use
                    // a local recording path; older Android falls back to AUTO's file-capable STT.
                    val fileSettings = if (
                        settings.sttEngine == SttEngineChoice.SYSTEM_LIVE &&
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                    ) settings.copy(sttEngine = SttEngineChoice.AUTO) else settings
                    runRecorderSession(fileSettings)
                } else if (settings.sttEngine == SttEngineChoice.SYSTEM_LIVE) {
                    runLiveSystemSession(settings)
                } else {
                    runRecorderSession(settings)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                VoiceLog.e("Dikte gagal", t)
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = t.message ?: t.javaClass.simpleName,
                    stage = "",
                )
            } finally {
                if (wakeWordPaused) graph.assistant.resumeWakeWordAfterDictation()
            }
        }
        return true
    }

    /** Selesai merekam dan lanjut ke transkripsi (dipakai tombol maupun auto-stop). */
    fun stopAndFinish() {
        if (_state.value.phase != Phase.LISTENING) return
        finishRequested = true
    }

    /** Batalkan tanpa memproses hasil. */
    fun cancel() {
        finishRequested = false
        cancelled = true
        amplitudeJob?.cancel()
        captureJob?.cancel()
        captureJob = null
        recorder.cancel()
        _state.value = _state.value.copy(phase = Phase.IDLE, stage = "", amplitude = 0f)
    }

    @Volatile
    private var finishRequested = false

    @Volatile
    private var cancelled = false

    // ---------------- Jalur 1: rekam sendiri lalu STT hybrid ----------------

    private suspend fun runRecorderSession(settings: AppSettings) {
        cancelled = false
        finishRequested = false
        if (!recorder.start(recordingsDir, prefix = "dikte")) {
            _state.value = _state.value.copy(
                phase = Phase.ERROR,
                error = "Tidak bisa mengakses mikrofon (mungkin sedang dipakai aplikasi lain)",
            )
            return
        }

        amplitudeJob = scope.launch {
            recorder.amplitude.collect { level ->
                _state.value = _state.value.copy(amplitude = level, elapsedMs = recorder.durationMs())
            }
        }

        // Bubble/wake-word tetap hands-free; pemicu tombol volume harus bertahan
        // sampai Volume Bawah dilepas, apa pun mode dikte umum yang dipilih.
        val source = _state.value.source
        val autoStop = source == Source.OVERLAY || source == Source.OVERLAY_PREVIEW || source == Source.WAKE_WORD ||
            (source != Source.VOLUME && settings.dictationTrigger != DictationTrigger.HOLD_TO_TALK)
        val timeout = settings.silenceTimeoutMs.toLong()

        while (!finishRequested && !cancelled) {
            delay(100)
            if (!recorder.isRunning) break
            if (autoStop && recorder.vad.hasSpeech && recorder.vad.silenceDurationMs(System.currentTimeMillis()) > timeout) {
                VoiceLog.d("Hening ${timeout}ms setelah bicara → hentikan otomatis")
                break
            }
        }

        amplitudeJob?.cancel()
        val recording: PcmRecording? = recorder.stopAndWait()

        if (cancelled) return
        if (recording == null) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = "Rekaman gagal disimpan")
            return
        }
        if (!recorder.vad.hasSpeech) {
            recording.wavFile.delete()
            _state.value = _state.value.copy(
                phase = Phase.ERROR,
                error = "Tidak ada suara terdeteksi. Coba bicara lebih dekat ke mikrofon.",
            )
            return
        }

        transcribeAndDeliver(recording, settings)
    }

    // ---------------- Jalur 2: recognizer sistem (siaran langsung) ----------------

    private suspend fun runLiveSystemSession(settings: AppSettings) {
        _state.value = _state.value.copy(stage = "Mendengarkan (mesin bawaan Android)…")
        try {
            val transcript = graph.stt.listenLive(
                settings = settings,
                onPartial = { partial -> _state.value = _state.value.copy(partial = partial) },
                onLevel = { level -> _state.value = _state.value.copy(amplitude = level) },
                timeoutMs = 20_000L,
                silenceTimeoutMs = settings.silenceTimeoutMs.toLong(),
            )
            deliver(transcript.text, settings, engineLabel = transcript.engine, transcript = null)
        } catch (t: Throwable) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = t.message ?: "Gagal mengenali suara")
        }
    }

    // ---------------- Setelah audio siap ----------------

    private suspend fun transcribeAndDeliver(recording: PcmRecording, settings: AppSettings) {
        _state.value = _state.value.copy(
            phase = Phase.TRANSCRIBING,
            stage = "Mengubah suara menjadi teks…",
            amplitude = 0f,
        )

        if (settings.speakerVerificationEnabled) {
            _state.value = _state.value.copy(stage = context.getString(R.string.dictation_speaker_checking))
            val check = try {
                graph.speakerVerification.verifyWav(recording.wavFile)
            } catch (cancelled: CancellationException) {
                recording.wavFile.delete()
                throw cancelled
            } catch (t: Throwable) {
                VoiceLog.w("Pemeriksaan speaker lokal gagal: ${t.message}")
                recording.wavFile.delete()
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = context.getString(R.string.dictation_speaker_check_failed),
                    stage = "",
                    amplitude = 0f,
                )
                return
            }
            if (check is dev.retrobit.voice.speaker.SpeakerVerificationManager.CheckResult.Rejected) {
                recording.wavFile.delete()
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = context.getString(R.string.dictation_speaker_rejected),
                    stage = "",
                    amplitude = 0f,
                )
                return
            }
        }

        val transcript = try {
            graph.stt.transcribe(
                recording = recording,
                settings = settings,
                onStage = { stage -> _state.value = _state.value.copy(stage = stage) },
                onPartial = { partial -> _state.value = _state.value.copy(partial = partial) },
            )
        } catch (t: Throwable) {
            VoiceLog.e("Transkripsi gagal", t)
            val detail = when (t) {
                is dev.retrobit.voice.stt.SttAllFailedException ->
                    "Semua mesin gagal → " + t.attempts.joinToString(" · ") { "${it.first}: ${it.second}" }
                else -> t.message ?: t.javaClass.simpleName
            }
            _state.value = _state.value.copy(phase = Phase.ERROR, error = detail)
            return
        } finally {
            // Simpan audio hanya kalau pengguna memang butuh; default hapus agar hemat ruang.
            recording.wavFile.delete()
        }

        deliver(transcript.text, settings, engineLabel = transcript.engine, transcript = transcript)
    }

    private suspend fun deliver(
        rawTextRaw: String,
        settings: AppSettings,
        engineLabel: String,
        transcript: dev.retrobit.voice.core.Transcript?,
    ) {
        val rawText = rawTextRaw.trim()
        if (rawText.isBlank()) {
            _state.value = _state.value.copy(phase = Phase.ERROR, error = "Hasil transkripsi kosong")
            return
        }

        // 1. Rapikan
        _state.value = _state.value.copy(phase = Phase.CLEANING, stage = "Merapikan teks…", rawText = rawText)
        val cleaned = clean(rawText, settings)

        // 2. Riwayat
        transcript?.let { graph.history.addDictation(it.copy(text = cleaned), settings.saveHistory) }

        // 3. Tempel (kecuali kalau sumbernya asisten — asisten memakai teksnya sendiri)
        _state.value = _state.value.copy(
            phase = Phase.INSERTING,
            stage = "Menyisipkan teks…",
            finalText = cleaned,
            engine = engineLabel,
        )

        val source = _state.value.source
        if (source != Source.ASSISTANT && source != Source.MANUAL && source != Source.OVERLAY_PREVIEW) {
            val result = graph.textInserter.insert(cleaned)
            if (!result.success) {
                _state.value = _state.value.copy(
                    phase = Phase.ERROR,
                    error = result.message,
                    insertChannel = result.channel.label,
                    stage = "",
                    amplitude = 0f,
                )
                return
            }
            _state.value = _state.value.copy(
                insertChannel = result.channel.label,
                stage = result.message,
            )
        }

        // Mode manual menampilkan hasil di Retrobit; tombol Salin di layar hasil
        // tetap tersedia sebagai tindakan eksplisit, bukan fallback penempelan.
        _state.value = _state.value.copy(phase = Phase.DONE, stage = "", amplitude = 0f)
    }

    /** Pembersihan teks: lokal oleh aturan, atau lewat LLM kalau diaktifkan. */
    private suspend fun clean(raw: String, settings: AppSettings): String {
        val localOptions = LocalTextCleaner.Options(
            autoPunctuation = settings.autoPunctuation,
            autoCapitalize = settings.autoCapitalize,
        )
        return when (settings.cleanupMode) {
            CleanupMode.OFF -> raw
            CleanupMode.LOCAL -> LocalTextCleaner.clean(raw, localOptions)
            CleanupMode.AI -> {
                if (!settings.llmReady) {
                    VoiceLog.w("AI cleanup dipilih tapi LLM belum siap → jatuh ke pembersihan lokal")
                    return LocalTextCleaner.clean(raw, localOptions)
                }
                try {
                    val result = graph.llm.complete(
                        settings = settings,
                        systemPrompt = dev.retrobit.voice.ai.Prompts.cleanup(settings.dictationLanguage),
                        userText = raw,
                        temperature = 0.1,
                    ).trim().trim('"')
                    if (result.isBlank()) LocalTextCleaner.clean(raw, localOptions) else result
                } catch (t: Throwable) {
                    VoiceLog.w("AI cleanup gagal: ${t.message} → fallback lokal")
                    LocalTextCleaner.clean(raw, localOptions)
                }
            }
        }
    }

    /**
     * Transkripsi berkas audio tanpa menempelkan ke mana pun.
     * Dipakai layar "Coba Dikte" dan fitur "impor audio" di masa depan.
     */
    suspend fun transcribeFileOnly(file: File, settings: AppSettings): Result<String> = withContext(Dispatchers.Default) {
        try {
            val recording = PcmRecording(
                wavFile = file,
                sampleRate = dev.retrobit.voice.audio.WavWriter.SAMPLE_RATE,
                durationMs = dev.retrobit.voice.audio.WavWriter.durationMsOf(file),
                peakRms = 1f,
            )
            val transcript = graph.stt.transcribe(recording, settings)
            Result.success(clean(transcript.text, settings))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /** Dengarkan sekali lalu kembalikan teks (dipakai asisten & wake word). */
    suspend fun listenForText(
        settings: AppSettings,
        silenceTimeoutMs: Long = settings.silenceTimeoutMs.toLong(),
        onPartial: (String) -> Unit = {},
    ): Result<String> {
        if (!hasMicrophonePermission()) return Result.failure(IllegalStateException("Izin mikrofon belum diberikan"))
        if (_state.value.isBusy) return Result.failure(IllegalStateException("Dikte sedang berjalan"))

        val silenceWindowMs = silenceTimeoutMs.coerceIn(400L, 5_000L)
        var heard = ""
        val job = scope.launch {
            if (settings.sttEngine == SttEngineChoice.SYSTEM_LIVE) {
                val transcript = graph.stt.listenLive(
                    settings = settings,
                    onPartial = { partial ->
                        heard = partial
                        onPartial(partial)
                    },
                    onLevel = {},
                    silenceTimeoutMs = silenceWindowMs,
                )
                heard = transcript.text
                onPartial(heard)
            } else {
                if (!recorder.start(recordingsDir, prefix = "asisten")) return@launch
                while (recorder.isRunning) {
                    delay(100)
                    if (recorder.vad.hasSpeech &&
                        recorder.vad.silenceDurationMs(System.currentTimeMillis()) > silenceWindowMs
                    ) {
                        break
                    }
                    if (recorder.durationMs() > 20_000) break
                }
                val rec = recorder.stopAndWait() ?: return@launch
                if (!recorder.vad.hasSpeech) {
                    rec.wavFile.delete()
                    return@launch
                }
                try {
                    val transcript = graph.stt.transcribe(rec, settings, onPartial = onPartial)
                    heard = transcript.text
                    onPartial(heard)
                } finally {
                    rec.wavFile.delete()
                }
            }
        }
        job.join()
        return if (heard.isBlank()) Result.failure(IllegalStateException("Tidak ada ucapan yang terdengar"))
        else Result.success(heard.trim())
    }

    fun release() {
        amplitudeJob?.cancel()
        captureJob?.cancel()
        recorder.releaseAll()
    }
}
