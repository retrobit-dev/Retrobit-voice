package dev.retrobit.voice.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.Transcript
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transkripsi di perangkat memakai recognizer on-device milik Google
 * (tersedia di Android 12+ / Pixel & banyak perangkat modern).
 *
 * Sejak Android 13 (API 33) recognizer bisa diberi berkas audio lewat
 * `RecognizerIntent.EXTRA_AUDIO_SOURCE`, sehingga satu rekaman yang sama
 * bisa dicoba di perangkat lebih dulu lalu dikirim ke cloud kalau gagal.
 *
 * Semua pemanggilan SpeechRecognizer dipaksa di main thread karena framework
 * mensyaratkan looper utama.
 */
class AndroidOnDeviceSttEngine(private val context: Context) : SttEngine {

    override val id: String = "on-device"

    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** Transkripsi didukung sejak API 33 (Android 13). */
    fun supportsFileInjection(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    override suspend fun transcribe(
        recording: PcmRecording,
        language: String,
        onPartial: (String) -> Unit,
    ): Transcript {
        if (!isAvailable()) throw SttUnavailableException("Pengenalan suara on-device tidak tersedia di perangkat ini")
        if (!supportsFileInjection()) {
            throw SttUnavailableException("Transkripsi berkas di perangkat butuh Android 13+ (API 33)")
        }
        val started = System.currentTimeMillis()
        val text = withContext(Dispatchers.Main) {
            transcribeOnMain(recording, language, onPartial)
        }
        return Transcript(
            text = text.trim(),
            engine = id,
            latencyMs = System.currentTimeMillis() - started,
            language = language,
        )
    }

    private suspend fun transcribeOnMain(
        recording: PcmRecording,
        language: String,
        onPartial: (String) -> Unit,
    ): String {
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            ?: throw SttUnavailableException("Recognizer on-device gagal dibuat")

        val pfd = try {
            ParcelFileDescriptor.open(recording.wavFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            recognizer.destroy()
            throw SttFailedException("Tidak bisa membuka berkas rekaman", t)
        }

        return try {
            val result = withTimeoutOrNull<String>(RECOGNITION_TIMEOUT_MS) {
                suspendCancellableCoroutine<String> { cont ->
                    var finished = false
                    fun finish(block: () -> Unit) {
                        if (finished) return
                        finished = true
                        block()
                    }

                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                        override fun onPartialResults(partialResults: Bundle?) {
                            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?.let(onPartial)
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            finish {
                                if (cont.isActive) cont.resume(text)
                            }
                        }

                        override fun onError(error: Int) {
                            finish {
                                if (cont.isActive) cont.resumeWithException(mapError(error))
                            }
                        }
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        // === Kunci transkripsi berkas di perangkat (API 33+) ===
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pfd)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, recording.sampleRate)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    }

                    try {
                        recognizer.startListening(intent)
                    } catch (t: Throwable) {
                        finish {
                            if (cont.isActive) cont.resumeWithException(SttFailedException("startListening gagal", t))
                        }
                    }

                    cont.invokeOnCancellation {
                        runCatching { recognizer.cancel() }
                    }
                }
            } ?: throw SttFailedException("On-device timeout (${RECOGNITION_TIMEOUT_MS / 1000}s)")

            if (result.isBlank()) throw SttFailedException("On-device tidak menghasilkan teks")
            result
        } finally {
            runCatching { pfd.close() }
            withContext(Dispatchers.Main) { runCatching { recognizer.destroy() } }
        }
    }

    private fun mapError(error: Int): Exception = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            SttUnavailableException("Izin mikrofon ditolak untuk recognizer")
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            SttUnavailableException("Bahasa belum tersedia di paket on-device perangkat ini")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SttFailedException("Recognizer sedang sibuk, coba lagi")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH ->
            SttFailedException("Tidak ada ucapan yang terdeteksi")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            SttFailedException("Butuh koneksi untuk model bahasa ini")
        else -> SttFailedException("On-device error kode $error")
    }

    private companion object {
        const val RECOGNITION_TIMEOUT_MS = 60_000L
    }
}
