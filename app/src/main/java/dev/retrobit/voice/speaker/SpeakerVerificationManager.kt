package dev.retrobit.voice.speaker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.audio.PcmRecorder
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Consent-based local enrollment and pre-STT speaker check. Raw enrollment WAVs are deleted. */
class SpeakerVerificationManager(
    context: Context,
    private val graph: AppGraph,
) {
    data class UiState(
        val enrolled: Boolean = false,
        val enrollmentActive: Boolean = false,
        val capturingSample: Boolean = false,
        val sampleCount: Int = 0,
        val modelDownloading: Boolean = false,
        val modelProgress: Float? = null,
        val status: String = "",
        val error: String? = null,
    )

    sealed interface CheckResult {
        data class Accepted(val similarity: Float) : CheckResult
        data object Rejected : CheckResult
    }

    private val appContext = context.applicationContext
    private val profileStore = SpeakerProfileStore(appContext.filesDir)
    private val model = SpeakerEmbeddingModel(appContext)
    private val recorder = PcmRecorder()
    private val operationMutex = Mutex()
    private val enrollmentCommitLock = Any()
    private val enrollmentEmbeddings = mutableListOf<FloatArray>()
    private val _state = MutableStateFlow(UiState())

    @Volatile
    private var enrollmentGeneration = 0L

    init {
        // A force-stop can interrupt file cleanup; remove only this feature's raw cache WAVs.
        purgeRawEnrollmentSamples()
    }

    val state: StateFlow<UiState> = _state.asStateFlow()

    suspend fun refresh() {
        val enrolled = withContext(Dispatchers.IO) { runCatching { profileStore.hasProfile() }.getOrDefault(false) }
        if (!enrolled && graph.settings.current().speakerVerificationEnabled) {
            graph.settings.update { it.copy(speakerVerificationEnabled = false) }
        }
        _state.value = _state.value.copy(enrolled = enrolled)
    }

    fun reportMicrophonePermissionRequired() {
        _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_microphone_required))
    }

    /** Called only from the app's explicit consent dialog after the user accepts. */
    suspend fun beginEnrollment(consentConfirmed: Boolean) {
        if (!consentConfirmed) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_consent_required))
            return
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_microphone_required))
            return
        }
        if (
            graph.dictation.state.value.isBusy || graph.assistant.state.value.listening || graph.assistant.state.value.thinking ||
            graph.wakeWordEnrollment.state.value.enrollmentActive
        ) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_busy))
            return
        }
        val generation = synchronized(enrollmentCommitLock) {
            if (_state.value.enrollmentActive) return
            enrollmentGeneration += 1
            _state.value = _state.value.copy(
                enrollmentActive = true,
                capturingSample = false,
                sampleCount = 0,
                modelDownloading = true,
                modelProgress = 0f,
                status = appContext.getString(R.string.speaker_model_downloading),
                error = null,
            )
            enrollmentGeneration
        }
        synchronized(enrollmentEmbeddings) { enrollmentEmbeddings.clear() }
        try {
            graph.settings.update { it.copy(speakerVerificationEnabled = false) }
            if (!isEnrollmentGenerationCurrent(generation)) return
            model.prepareModel { downloaded, total ->
                val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                updateEnrollmentStateIfCurrent(generation) { it.copy(modelProgress = progress) }
            }
            if (!updateEnrollmentStateIfCurrent(generation) {
                    it.copy(
                        modelDownloading = false,
                        modelProgress = 1f,
                        status = appContext.getString(R.string.speaker_enrollment_ready, 1, ENROLLMENT_SAMPLES),
                        error = null,
                    )
                }
            ) return
        } catch (cancelled: CancellationException) {
            if (!updateEnrollmentStateIfCurrent(generation) {
                    it.copy(enrollmentActive = false, modelDownloading = false)
                }
            ) return
            throw cancelled
        } catch (error: Throwable) {
            if (!isEnrollmentGenerationCurrent(generation)) return
            VoiceLog.e("Model speaker tidak dapat disiapkan", error)
            updateEnrollmentStateIfCurrent(generation) {
                it.copy(
                    enrollmentActive = false,
                    modelDownloading = false,
                    modelProgress = null,
                    status = "",
                    error = appContext.getString(R.string.speaker_model_download_failed),
                )
            }
        }
    }

    suspend fun captureEnrollmentSample() = operationMutex.withLock {
        if (!_state.value.enrollmentActive || _state.value.modelDownloading || _state.value.capturingSample) return@withLock
        if (
            graph.dictation.state.value.isBusy || graph.assistant.state.value.listening || graph.assistant.state.value.thinking ||
            graph.wakeWordEnrollment.state.value.enrollmentActive
        ) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_busy))
            return@withLock
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _state.value = _state.value.copy(error = appContext.getString(R.string.speaker_microphone_required))
            return@withLock
        }

        val captureTicket = synchronized(enrollmentCommitLock) {
            val current = _state.value
            if (!current.enrollmentActive || current.modelDownloading || current.capturingSample) {
                null
            } else {
                val nextSample = current.sampleCount + 1
                _state.value = current.copy(
                    capturingSample = true,
                    status = appContext.getString(R.string.speaker_recording_sample, nextSample, ENROLLMENT_SAMPLES),
                    error = null,
                )
                enrollmentGeneration to nextSample
            }
        } ?: return@withLock
        val (generation, _) = captureTicket
        var wakeWordPaused = false
        var recordingFile: File? = null
        try {
            graph.assistant.pauseWakeWordForDictation()
            wakeWordPaused = true
            val recordingStarted = withContext(Dispatchers.IO) {
                synchronized(enrollmentCommitLock) {
                    if (enrollmentGeneration != generation) false
                    else recorder.start(recordingsDir, prefix = RAW_ENROLLMENT_PREFIX)
                }
            }
            if (!recordingStarted) {
                if (!isEnrollmentGenerationCurrent(generation)) throw CancellationException("Enrolmen dibatalkan")
                throw IllegalStateException(appContext.getString(R.string.speaker_recording_failed))
            }
            delay(SAMPLE_RECORDING_MS)
            if (!isEnrollmentGenerationCurrent(generation)) throw CancellationException("Enrolmen dibatalkan")
            val recording = withContext(Dispatchers.IO) { recorder.stopAndWait() }
                ?: throw IllegalStateException(appContext.getString(R.string.speaker_recording_failed))
            recordingFile = recording.wavFile
            if (!recorder.vad.hasSpeech) {
                throw SpeakerAudioFeatures.InvalidAudioException(appContext.getString(R.string.speaker_no_speech))
            }
            val embedding = model.embeddingFromWav(recording.wavFile)
            if (!isEnrollmentGenerationCurrent(generation)) throw CancellationException("Enrolmen dibatalkan")
            deleteRawEnrollmentSample(recording.wavFile)
            recordingFile = null
            val complete = synchronized(enrollmentCommitLock) {
                if (enrollmentGeneration != generation) throw CancellationException("Enrolmen dibatalkan")
                synchronized(enrollmentEmbeddings) {
                    enrollmentEmbeddings += embedding
                    enrollmentEmbeddings.size
                }
            }

            if (complete >= ENROLLMENT_SAMPLES) {
                val template = synchronized(enrollmentEmbeddings) {
                    SpeakerAudioFeatures.averageNormalized(enrollmentEmbeddings.toList())
                } ?: throw IllegalStateException(appContext.getString(R.string.speaker_embedding_failed))
                withContext(Dispatchers.IO) {
                    synchronized(enrollmentCommitLock) {
                        if (enrollmentGeneration != generation) throw CancellationException("Enrolmen dibatalkan")
                        profileStore.writeEmbedding(template)
                        _state.value = UiState(
                            enrolled = true,
                            status = appContext.getString(R.string.speaker_enrollment_complete),
                        )
                        synchronized(enrollmentEmbeddings) { enrollmentEmbeddings.clear() }
                    }
                }
                graph.settings.update { it.copy(speakerVerificationEnabled = false) }
            } else {
                if (!updateEnrollmentStateIfCurrent(generation) {
                        it.copy(
                            capturingSample = false,
                            sampleCount = complete,
                            status = appContext.getString(
                                R.string.speaker_enrollment_ready,
                                complete + 1,
                                ENROLLMENT_SAMPLES,
                            ),
                            error = null,
                        )
                    }
                ) return@withLock
            }
        } catch (cancelled: CancellationException) {
            if (updateEnrollmentStateIfCurrent(generation) {
                    it.copy(
                        capturingSample = false,
                        status = appContext.getString(
                            R.string.speaker_enrollment_ready,
                            it.sampleCount + 1,
                            ENROLLMENT_SAMPLES,
                        ),
                        error = null,
                    )
                }
            ) throw cancelled
        } catch (error: Throwable) {
            if (!isEnrollmentGenerationCurrent(generation)) return@withLock
            VoiceLog.e("Sampel enrolmen speaker gagal", error)
            updateEnrollmentStateIfCurrent(generation) {
                it.copy(
                    capturingSample = false,
                    error = when (error) {
                        is SpeakerAudioFeatures.InvalidAudioException -> error.message
                        else -> appContext.getString(R.string.speaker_enrollment_sample_failed)
                    },
                )
            }
        } finally {
            if (recorder.isRunning) withContext(NonCancellable + Dispatchers.IO) { recorder.cancel() }
            recordingFile?.let { file ->
                runCatching { deleteRawEnrollmentSample(file) }
                    .onFailure { VoiceLog.e("Rekaman enrolmen mentah tidak dapat dihapus", it) }
            }
            purgeRawEnrollmentSamples()
            if (wakeWordPaused) graph.assistant.resumeWakeWordAfterDictation()
        }
    }

    fun cancelEnrollment() {
        synchronized(enrollmentCommitLock) {
            enrollmentGeneration += 1
            recorder.cancel()
            synchronized(enrollmentEmbeddings) { enrollmentEmbeddings.clear() }
            val enrolled = _state.value.enrolled
            _state.value = UiState(
                enrolled = enrolled,
                status = if (enrolled) appContext.getString(R.string.speaker_profile_ready) else "",
            )
        }
        purgeRawEnrollmentSamples()
    }

    suspend fun verifyWav(wavFile: File): CheckResult {
        val settings = graph.settings.current()
        if (!settings.speakerVerificationEnabled) return CheckResult.Accepted(similarity = 1f)
        if (_state.value.enrollmentActive) throw IllegalStateException(appContext.getString(R.string.speaker_busy))
        val template = withContext(Dispatchers.IO) { profileStore.readEmbedding() }
            ?: throw IllegalStateException(appContext.getString(R.string.speaker_profile_missing))
        val actual = model.embeddingFromWav(wavFile)
        val similarity = SpeakerAudioFeatures.cosineSimilarity(template, actual)
            ?: throw IllegalStateException(appContext.getString(R.string.speaker_embedding_failed))
        return if (similarity >= SIMILARITY_THRESHOLD) {
            CheckResult.Accepted(similarity)
        } else {
            CheckResult.Rejected
        }
    }

    suspend fun deleteEnrollment() {
        cancelEnrollment()
        operationMutex.withLock {
            graph.settings.update { it.copy(speakerVerificationEnabled = false) }
            withContext(Dispatchers.IO) { profileStore.clear() }
            _state.value = UiState()
        }
    }

    private fun isEnrollmentGenerationCurrent(generation: Long): Boolean = enrollmentGeneration == generation

    private fun updateEnrollmentStateIfCurrent(
        generation: Long,
        transform: (UiState) -> UiState,
    ): Boolean = synchronized(enrollmentCommitLock) {
        if (enrollmentGeneration != generation) {
            false
        } else {
            _state.value = transform(_state.value)
            true
        }
    }

    private val recordingsDir: File
        get() = File(appContext.cacheDir, "speaker-enrollment").apply { if (!exists()) mkdirs() }

    private fun deleteRawEnrollmentSample(file: File) {
        if (file.exists() && !file.delete() && file.exists()) {
            throw IllegalStateException("Sampel suara mentah tidak dapat dihapus")
        }
    }

    private fun purgeRawEnrollmentSamples() {
        recordingsDir.listFiles()
            ?.filter { it.name.startsWith("$RAW_ENROLLMENT_PREFIX-") && it.extension.equals("wav", ignoreCase = true) }
            ?.forEach { file ->
                if (!file.delete() && file.exists()) {
                    VoiceLog.e("Rekaman enrolmen mentah tersisa di cache: ${file.name}")
                }
            }
    }

    companion object {
        private const val RAW_ENROLLMENT_PREFIX = "enrolmen-speaker"
        const val ENROLLMENT_SAMPLES = 3
        const val SAMPLE_RECORDING_MS = 4_000L
        const val SIMILARITY_THRESHOLD = 0.70f
    }
}
