package dev.retrobit.voice.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.audio.PcmRecorder
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Consent-based enrollment of three local wake-phrase examples; raw PCM is deleted after extraction. */
class WakeWordEnrollmentManager(
    context: Context,
    private val graph: AppGraph,
    private val kws: LocalWakeWordKws,
) {
    data class UiState(
        val profileReady: Boolean = false,
        val enrollmentActive: Boolean = false,
        val capturingSample: Boolean = false,
        val sampleCount: Int = 0,
        val modelLoading: Boolean = false,
        val status: String = "",
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val profileStore = WakeWordProfileStore(appContext.noBackupFilesDir)
    private val recorder = PcmRecorder(maxDurationMs = SAMPLE_RECORDING_MS + 1_000L)
    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val templates = mutableListOf<WakeWordEmbeddingSequence>()
    private val _state = MutableStateFlow(UiState())

    @Volatile
    private var activePhrase = ""

    @Volatile
    private var generation = 0L

    init {
        purgeRawEnrollmentSamples()
    }

    val state: StateFlow<UiState> = _state.asStateFlow()

    suspend fun hasProfileForPhrase(phrase: String): Boolean = kws.hasProfileForPhrase(phrase)

    suspend fun refresh(phrase: String) {
        val normalizedPhrase = WakeWordAudioFeatures.normalizePhrase(phrase)
        val current = _state.value
        if (current.enrollmentActive && activePhrase != normalizedPhrase) {
            cancelEnrollment()
        }
        val ready = runCatching { kws.hasProfileForPhrase(normalizedPhrase) }
            .onFailure { VoiceLog.e("Profil wake word tidak dapat dibaca", it) }
            .getOrDefault(false)
        if (WakeWordAudioFeatures.normalizePhrase(graph.settings.current().wakeWordPhrase) != normalizedPhrase) return
        val updated = _state.value
        _state.value = updated.copy(
            profileReady = ready,
            status = when {
                updated.enrollmentActive -> updated.status
                ready -> appContext.getString(R.string.wake_word_profile_ready)
                normalizedPhrase.isBlank() -> appContext.getString(R.string.wake_word_phrase_empty)
                else -> appContext.getString(R.string.wake_word_profile_missing)
            },
            error = if (updated.enrollmentActive) updated.error else null,
        )
    }

    fun reportMicrophonePermissionRequired() {
        _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_microphone_required))
    }

    /** Called only after the user explicitly chooses to record local wake-phrase examples. */
    suspend fun beginEnrollment(phrase: String) {
        val normalizedPhrase = WakeWordAudioFeatures.normalizePhrase(phrase)
        if (normalizedPhrase.isBlank()) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_phrase_empty))
            return
        }
        if (normalizedPhrase.length > WakeWordProfileCodec.MAX_PHRASE_LENGTH) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_phrase_too_long))
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportMicrophonePermissionRequired()
            return
        }
        if (isBusy()) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_busy))
            return
        }

        val ticket = synchronized(stateLock) {
            if (_state.value.enrollmentActive) return
            generation += 1
            activePhrase = normalizedPhrase
            templates.clear()
            _state.value = _state.value.copy(
                enrollmentActive = true,
                capturingSample = false,
                sampleCount = 0,
                modelLoading = true,
                status = appContext.getString(R.string.wake_word_model_loading),
                error = null,
            )
            generation
        }

        try {
            // Ensure the passive AudioRecord is fully released before enrollment uses the microphone.
            graph.assistant.stopWakeWordForEnrollment()
            WakeWordService.stop(appContext)
            kws.prepare()
            if (!isCurrent(ticket)) throw CancellationException("Enrolmen wake word dibatalkan")
            _state.value = _state.value.copy(
                modelLoading = false,
                status = appContext.getString(R.string.wake_word_enrollment_ready, normalizedPhrase, 1, REQUIRED_SAMPLES),
                error = null,
            )
        } catch (cancelled: CancellationException) {
            if (isCurrent(ticket)) finishEnrollment(ticket, restoreProfileState = true)
            throw cancelled
        } catch (error: Throwable) {
            if (!isCurrent(ticket)) return
            VoiceLog.e("Model embedding wake word gagal disiapkan", error)
            finishEnrollment(ticket, restoreProfileState = true)
            _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_model_failed))
        }
    }

    suspend fun captureEnrollmentSample() = operationMutex.withLock {
        val current = _state.value
        if (!current.enrollmentActive || current.modelLoading || current.capturingSample) return@withLock
        if (isBusy()) {
            _state.value = current.copy(error = appContext.getString(R.string.wake_word_busy))
            return@withLock
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = current.copy(error = appContext.getString(R.string.wake_word_microphone_required))
            return@withLock
        }

        val ticket = synchronized(stateLock) {
            if (!_state.value.enrollmentActive || _state.value.modelLoading || _state.value.capturingSample) {
                null
            } else {
                _state.value = _state.value.copy(
                    capturingSample = true,
                    status = appContext.getString(
                        R.string.wake_word_recording_sample,
                        _state.value.sampleCount + 1,
                        REQUIRED_SAMPLES,
                    ),
                    error = null,
                )
                generation
            }
        } ?: return@withLock

        var recordingFile: File? = null
        try {
            val started = withContext(Dispatchers.IO) {
                synchronized(stateLock) {
                    if (generation != ticket) false
                    else recorder.start(recordingsDir, prefix = RAW_SAMPLE_PREFIX)
                }
            }
            if (!started) {
                if (!isCurrent(ticket)) throw CancellationException("Enrolmen wake word dibatalkan")
                throw IllegalStateException(appContext.getString(R.string.wake_word_recording_failed))
            }

            val recordingDeadline = SystemClock.elapsedRealtime() + SAMPLE_RECORDING_MS
            while (recorder.isRunning && SystemClock.elapsedRealtime() < recordingDeadline) {
                delay(20)
            }
            if (!isCurrent(ticket)) throw CancellationException("Enrolmen wake word dibatalkan")
            val recording = withContext(Dispatchers.IO) { recorder.stopAndWait() }
                ?: throw IllegalStateException(appContext.getString(R.string.wake_word_recording_failed))
            recordingFile = recording.wavFile
            if (!recorder.vad.hasSpeech) throw WakeWordAudioFeatures.InvalidAudioException(
                appContext.getString(R.string.wake_word_no_speech),
            )

            val pcm = withContext(Dispatchers.IO) { WakeWordAudioFeatures.readPcm16MonoWav(recording.wavFile) }
            val template = kws.createTemplate(pcm)
            if (!isCurrent(ticket)) throw CancellationException("Enrolmen wake word dibatalkan")
            deleteRawSample(recording.wavFile)
            recordingFile = null

            val currentTemplates = synchronized(stateLock) {
                if (generation != ticket) throw CancellationException("Enrolmen wake word dibatalkan")
                templates += template
                templates.toList()
            }
            if (currentTemplates.size < REQUIRED_SAMPLES) {
                _state.value = _state.value.copy(
                    capturingSample = false,
                    sampleCount = currentTemplates.size,
                    status = appContext.getString(
                        R.string.wake_word_sample_added,
                        currentTemplates.size,
                        REQUIRED_SAMPLES,
                    ),
                    error = null,
                )
            } else {
                val phrase = activePhrase
                withContext(Dispatchers.IO) {
                    synchronized(stateLock) {
                        if (generation != ticket) throw CancellationException("Enrolmen wake word dibatalkan")
                        profileStore.writeProfile(phrase, currentTemplates)
                    }
                }
                VoiceLog.i("Profil wake word lokal terenkripsi tersimpan (${currentTemplates.size} sampel)")
                synchronized(stateLock) {
                    if (generation != ticket) throw CancellationException("Enrolmen wake word dibatalkan")
                    _state.value = _state.value.copy(
                        profileReady = true,
                        enrollmentActive = false,
                        capturingSample = false,
                        sampleCount = REQUIRED_SAMPLES,
                        modelLoading = false,
                        status = appContext.getString(R.string.wake_word_enrollment_complete),
                        error = null,
                    )
                    templates.clear()
                }
                VoiceLog.i("Enrolmen wake word selesai untuk frasa lokal")
            }
        } catch (cancelled: CancellationException) {
            recorder.cancel()
            if (isCurrent(ticket)) finishEnrollment(ticket, restoreProfileState = true)
            throw cancelled
        } catch (error: Throwable) {
            VoiceLog.e("Sampel wake word gagal diproses", error)
            val retainedSamples = synchronized(stateLock) {
                if (generation == ticket && templates.size >= REQUIRED_SAMPLES) {
                    templates.removeAt(templates.lastIndex)
                }
                templates.size
            }
            _state.value = _state.value.copy(
                capturingSample = false,
                sampleCount = retainedSamples,
                status = appContext.getString(R.string.wake_word_enrollment_retry),
                error = when (error) {
                    is WakeWordAudioFeatures.InvalidAudioException -> error.message
                    else -> appContext.getString(R.string.wake_word_recording_failed)
                },
            )
        } finally {
            recorder.cancel()
            recordingFile?.let { file ->
                runCatching { deleteRawSample(file) }
                    .onFailure { VoiceLog.e("Rekaman wake word mentah tidak dapat dihapus", it) }
            }
        }
    }

    suspend fun cancelEnrollment() {
        val ticket = synchronized(stateLock) {
            if (!_state.value.enrollmentActive && !_state.value.capturingSample) return
            generation += 1
            generation
        }
        // stop() unblocks AudioRecord.read if a sample is currently being captured.
        recorder.cancel()
        operationMutex.withLock {
            finishEnrollment(ticket, restoreProfileState = true)
        }
    }

    suspend fun deleteProfile() {
        cancelEnrollment()
        graph.assistant.stopWakeWordForEnrollment()
        WakeWordService.stop(appContext)
        try {
            withContext(Dispatchers.IO) { profileStore.clear() }
            _state.value = UiState(
                profileReady = false,
                status = appContext.getString(R.string.wake_word_profile_missing),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            VoiceLog.e("Profil wake word gagal dihapus", error)
            _state.value = _state.value.copy(error = appContext.getString(R.string.wake_word_delete_failed))
        }
    }

    private fun isBusy(): Boolean = graph.dictation.state.value.isBusy ||
        graph.assistant.state.value.listening || graph.assistant.state.value.thinking ||
        graph.speakerVerification.state.value.enrollmentActive

    private fun isCurrent(ticket: Long): Boolean = generation == ticket

    private suspend fun finishEnrollment(ticket: Long, restoreProfileState: Boolean) {
        if (!isCurrent(ticket)) return
        synchronized(stateLock) {
            if (generation != ticket) return
            activePhrase = ""
            templates.clear()
            _state.value = _state.value.copy(
                enrollmentActive = false,
                capturingSample = false,
                modelLoading = false,
                sampleCount = 0,
                error = null,
            )
        }
        recorder.cancel()
        if (restoreProfileState) refresh(graph.settings.current().wakeWordPhrase)
    }

    private val recordingsDir: File
        get() = File(appContext.cacheDir, "wake-word-enrollment").apply { if (!exists()) mkdirs() }

    private fun deleteRawSample(file: File) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IllegalStateException("Rekaman wake word mentah tidak dapat dihapus: ${file.name}")
        }
    }

    private fun purgeRawEnrollmentSamples() {
        recordingsDir.listFiles()
            ?.filter { it.name.startsWith("$RAW_SAMPLE_PREFIX-") && it.extension.equals("wav", ignoreCase = true) }
            ?.forEach { file ->
                runCatching { deleteRawSample(file) }
                    .onFailure { VoiceLog.e("Rekaman wake word mentah lama tidak dapat dihapus", it) }
            }
    }

    companion object {
        const val REQUIRED_SAMPLES = 3
        const val SAMPLE_RECORDING_MS = 3_000L
        private const val RAW_SAMPLE_PREFIX = "wakeword-enroll"
    }
}
