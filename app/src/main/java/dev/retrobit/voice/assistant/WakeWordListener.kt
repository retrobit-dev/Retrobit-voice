package dev.retrobit.voice.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.retrobit.voice.R
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Personalized on-device wake-word monitor. It reads short PCM frames with AudioRecord and
 * compares local speech embeddings against an enrolled phrase profile; it never starts
 * SpeechRecognizer or requests audio focus.
 */
class WakeWordListener(
    private val context: Context,
    private val phrase: String,
    private val kws: LocalWakeWordKws,
    private val onWake: (String) -> Unit,
    private val onStateChange: (String) -> Unit = {},
    private val onTerminated: (WakeWordListener) -> Unit = {},
    /** Stop holding the microphone while dictation, assistant speech input, or enrollment is active. */
    private val isYielded: () -> Boolean = { false },
) {
    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    private val normalizedPhrase = WakeWordAudioFeatures.normalizePhrase(phrase)

    fun start(scope: CoroutineScope) {
        if (running) return
        if (normalizedPhrase.isBlank()) {
            onStateChange(context.getString(R.string.wake_word_phrase_empty))
            onTerminated(this)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onStateChange(context.getString(R.string.wake_word_microphone_required))
            onTerminated(this)
            return
        }

        running = true
        val previousJob = job
        job = scope.launch {
            previousJob?.cancelAndJoin()
            onStateChange(context.getString(R.string.wake_word_model_loading))
            while (isActive && running) {
                if (isYielded()) {
                    delay(YIELD_POLL_MS)
                    continue
                }
                try {
                    kws.prepare()
                    val profile = kws.profileForPhrase(normalizedPhrase)
                    if (profile == null) {
                        running = false
                        onStateChange(context.getString(R.string.wake_word_profile_missing))
                        onTerminated(this@WakeWordListener)
                        return@launch
                    }
                    if (isYielded()) {
                        delay(YIELD_POLL_MS)
                        continue
                    }
                    onStateChange(context.getString(R.string.wake_word_listener_active, phrase))
                    val detected = listenForWake(profile)
                    if (detected || !running) break
                    delay(if (isYielded()) YIELD_POLL_MS else RESTART_DELAY_MS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    VoiceLog.e("Detektor lokal wake word gagal", error)
                    if (
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        running = false
                        onStateChange(context.getString(R.string.wake_word_microphone_required))
                        onTerminated(this@WakeWordListener)
                        return@launch
                    }
                    onStateChange(context.getString(R.string.wake_word_listener_error))
                    delay(ERROR_RETRY_DELAY_MS)
                }
            }
        }
    }

    fun stop() {
        running = false
        stopActiveRecord()
        val activeJob = job
        job = null
        activeJob?.cancel()
        onStateChange(context.getString(R.string.wake_word_listener_stopped))
    }

    /** Stop capture and wait until AudioRecord is released on its worker coroutine. */
    suspend fun stopAndJoin() {
        running = false
        stopActiveRecord()
        val activeJob = job
        activeJob?.cancelAndJoin()
        if (job === activeJob) job = null
        onStateChange(context.getString(R.string.wake_word_listener_stopped))
    }

    @SuppressLint("MissingPermission")
    private suspend fun listenForWake(profile: WakeWordProfile): Boolean = withContext(Dispatchers.IO) {
        val minimumBufferBytes = AudioRecord.getMinBufferSize(
            WakeWordAudioFeatures.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimumBufferBytes <= 0) throw IllegalStateException("AudioRecord menolak format PCM 16 kHz")

        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(WakeWordAudioFeatures.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimumBufferBytes * 2, READ_BUFFER_SAMPLES * 4))
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException("AudioRecord wake word tidak dapat diinisialisasi")
        }

        activeRecord = recorder
        val ring = FloatRingBuffer(WINDOW_SAMPLES)
        val pcm = ShortArray(READ_BUFFER_SAMPLES)
        var samplesUntilInference = STEP_SAMPLES
        var matchingWindows = 0
        var lastMatchAtMs = 0L
        var detected = false
        try {
            recorder.startRecording()
            while (running && currentCoroutineContext().isActive) {
                if (isYielded()) break
                val read = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (read == AudioRecord.ERROR_DEAD_OBJECT) throw IllegalStateException("AudioRecord wake word terputus")
                if (read < 0) throw IllegalStateException("AudioRecord read() gagal: $read")
                if (read == 0) continue

                ring.append(pcm, read)
                samplesUntilInference -= read
                if (samplesUntilInference > 0) continue
                samplesUntilInference += STEP_SAMPLES
                if (ring.samplesReceived < WakeWordAudioFeatures.MIN_MODEL_SAMPLES) continue

                val window = ring.snapshot()
                if (WakeWordAudioFeatures.rms(window) < MIN_WINDOW_RMS) {
                    matchingWindows = 0
                    continue
                }
                val distance = try {
                    kws.distanceToProfile(window, profile)
                } catch (_: WakeWordAudioFeatures.InvalidAudioException) {
                    null
                }
                if (!running || !currentCoroutineContext().isActive) break
                val now = System.currentTimeMillis()
                if (distance != null && distance <= MATCH_THRESHOLD) {
                    if (now - lastMatchAtMs <= MAX_CONFIRMATION_GAP_MS) {
                        matchingWindows += 1
                    } else {
                        matchingWindows = 1
                    }
                    lastMatchAtMs = now
                    if (matchingWindows >= REQUIRED_MATCHING_WINDOWS) {
                        detected = true
                        VoiceLog.i("Wake word lokal cocok dengan profil (jarak=${"%.3f".format(distance)})")
                        running = false
                        break
                    }
                } else {
                    matchingWindows = 0
                    lastMatchAtMs = 0L
                }
            }
        } finally {
            if (activeRecord === recorder) activeRecord = null
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            recorder.release()
        }

        if (detected) {
            withContext(Dispatchers.Main.immediate) {
                onStateChange(context.getString(R.string.wake_word_detected))
                onWake(phrase)
            }
        }
        detected
    }

    private fun stopActiveRecord() {
        activeRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
        }
    }

    private class FloatRingBuffer(private val capacity: Int) {
        private val values = FloatArray(capacity)
        private var writeIndex = 0

        var samplesReceived: Long = 0
            private set

        fun append(samples: ShortArray, count: Int) {
            for (index in 0 until count) {
                values[writeIndex] = samples[index].toFloat() / 32768f
                writeIndex = (writeIndex + 1) % capacity
            }
            samplesReceived += count
        }

        fun snapshot(): FloatArray {
            val result = FloatArray(capacity)
            val first = writeIndex
            val firstPart = capacity - first
            values.copyInto(result, 0, first, capacity)
            if (first > 0) values.copyInto(result, firstPart, 0, first)
            return result
        }
    }

    private companion object {
        const val READ_BUFFER_SAMPLES = 1_600 // 100 ms.
        const val WINDOW_SAMPLES = WakeWordAudioFeatures.SAMPLE_RATE_HZ * 2
        const val STEP_SAMPLES = WakeWordAudioFeatures.SAMPLE_RATE_HZ / 4 // 250 ms.
        const val MIN_WINDOW_RMS = 0.0025f
        const val MATCH_THRESHOLD = 0.14f
        const val REQUIRED_MATCHING_WINDOWS = 2
        const val MAX_CONFIRMATION_GAP_MS = 900L
        const val RESTART_DELAY_MS = 250L
        const val ERROR_RETRY_DELAY_MS = 2_000L
        const val YIELD_POLL_MS = 500L
    }
}
