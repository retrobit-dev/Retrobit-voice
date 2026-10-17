package dev.retrobit.voice.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Perekam mikrofon: PCM 16-bit mono 16 kHz ke berkas WAV.
 *
 * Kenapa merekam sendiri (bukan langsung menyerahkan ke SpeechRecognizer)?
 * Karena dengan begitu satu rekaman bisa dicoba ke beberapa mesin STT
 * (on-device dulu, cloud sebagai cadangan) — inti dari mode hybrid.
 */
class PcmRecorder(
    private val sampleRate: Int = WavWriter.SAMPLE_RATE,
    private val maxDurationMs: Long = 180_000L,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var raf: RandomAccessFile? = null
    private var file: File? = null
    private var startedAt = 0L

    val vad = VoiceActivityDetector()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    @Volatile
    private var running = false

    @Volatile
    private var activeRecord: AudioRecord? = null

    val isRunning: Boolean get() = running
    fun durationMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    /** Sumber audio terbaik untuk pengenalan suara (memakai noise suppression & AGC bawaan). */
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    @SuppressLint("MissingPermission")
    fun start(outputDir: File, prefix: String = "dikte"): Boolean {
        if (running) return false
        val out = WavWriter.newFile(outputDir, prefix)
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            VoiceLog.e("AudioRecord.getMinBufferSize gagal: $minBuffer")
            out.delete()
            return false
        }
        val bufferBytes = maxOf(minBuffer * 2, sampleRate / 5 * 2) // ~200 ms

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (t: Throwable) {
            VoiceLog.e("Gagal membuat AudioRecord", t)
            out.delete()
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            VoiceLog.e("AudioRecord tidak terinisialisasi (izin mikrofon?)")
            record.release()
            out.delete()
            return false
        }

        val randomAccess = try {
            RandomAccessFile(out, "rw").apply {
                WavWriter.writePlaceholderHeader(out, sampleRate)
                seek(WavWriter.HEADER_SIZE.toLong())
            }
        } catch (t: Throwable) {
            VoiceLog.e("Gagal membuka berkas rekaman", t)
            record.release()
            out.delete()
            return false
        }

        activeRecord = record
        this.raf = randomAccess
        this.file = out
        startedAt = System.currentTimeMillis()
        running = true
        vad.reset()

        try {
            record.startRecording()
        } catch (t: Throwable) {
            VoiceLog.e("Gagal startRecording", t)
            release()
            out.delete()
            return false
        }

        job = scope.launch { readLoop(record, randomAccess) }
        return true
    }

    private fun readLoop(record: AudioRecord, out: RandomAccessFile) {
        val chunkFrames = (sampleRate * 0.02).toInt() // 20 ms
        val buffer = ShortArray(chunkFrames)
        val bytes = ByteArray(chunkFrames * 2)
        var framesSinceAmplitudeUpdate = 0
        while (running && activeRecord === record) {
            val read = try {
                record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            } catch (t: Throwable) {
                VoiceLog.e("read() AudioRecord gagal", t)
                break
            }
            if (read < 0) {
                if (running && activeRecord === record) {
                    VoiceLog.e("read() AudioRecord mengembalikan error: $read")
                }
                break
            }
            if (read == 0) continue

            var sumSquares = 0.0
            for (i in 0 until read) {
                val s = buffer[i].toInt()
                sumSquares += (s.toDouble() * s.toDouble())
                bytes[i * 2] = (s and 0xff).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
            }
            val rms = sqrt(sumSquares / read / (32768.0 * 32768.0)).toFloat()

            try {
                out.write(bytes, 0, read * 2)
            } catch (t: Throwable) {
                VoiceLog.e("Gagal menulis audio", t)
                break
            }

            val frameMs = (read * 1000) / sampleRate
            vad.process(rms, frameMs, System.currentTimeMillis())

            framesSinceAmplitudeUpdate++
            if (framesSinceAmplitudeUpdate >= 3) {
                framesSinceAmplitudeUpdate = 0
                _amplitude.value = (rms * 12f).coerceIn(0f, 1f)
            }

            if (durationMs() >= maxDurationMs) {
                VoiceLog.w("Rekaman mencapai batas maksimum, dihentikan otomatis")
                break
            }
        }
    }

    /** Hentikan rekaman dan kembalikan berkas yang siap dikirim ke mesin STT. */
    fun stop(): PcmRecording? {
        if (!running) return null
        running = false
        val currentFile = file
        val duration = durationMs()
        val peak = _amplitude.value
        release()

        val f = currentFile ?: return null
        WavWriter.finalizeHeader(f, sampleRate)
        val finalDuration = if (duration > 0) duration else WavWriter.durationMsOf(f, sampleRate)
        VoiceLog.i("Rekaman selesai: ${f.name}, ${finalDuration}ms, ucapan=${vad.hasSpeech}")
        return PcmRecording(f, sampleRate, finalDuration, peak)
    }

    fun cancel() {
        running = false
        val f = file
        release()
        job?.cancel()
        f?.delete()
    }

    suspend fun stopAndWait(): PcmRecording? {
        val result = stop()
        job?.cancelAndJoin()
        return result
    }

    private fun release() {
        running = false
        activeRecord?.let { record ->
            runCatching {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }
            runCatching { record.release() }
        }
        activeRecord = null
        runCatching { raf?.close() }
        raf = null
        _amplitude.value = 0f
    }

    fun releaseAll() {
        release()
        job?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        /** Dipakai oleh UI untuk menunjukkan izin mikrofon diperlukan. */
        const val PERMISSION = android.Manifest.permission.RECORD_AUDIO
    }
}
