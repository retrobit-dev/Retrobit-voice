package dev.retrobit.voice.stt

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.Transcript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Mode "langsung": microphone diserahkan sepenuhnya ke recognizer sistem.
 * Dipakai ketika pengguna memilih mesin bawaan Android, karena recognizer
 * punya keunggulan sendiri (endpointing, adaptasi bahasa, microfon langsung).
 *
 * Menghasilkan hasil parsial untuk umpan balik waktu-nyata.
 */
class AndroidSystemSttEngine(private val context: Context) : SttEngine {

    override val id: String = "android-live"

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun transcribe(
        recording: PcmRecording,
        language: String,
        onPartial: (String) -> Unit,
    ): Transcript {
        throw SttUnavailableException("Mesin ini hanya mendukung mode siaran langsung")
    }

    /**
     * Buka sesi pengenalan langsung. Blok sampai hasil final atau timeout.
     * [onPartial] dipanggil untuk setiap hasil sementara.
     */
    suspend fun listen(
        language: String,
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
        preferOnDevice: Boolean = true,
        timeoutMs: Long = 20_000L,
        silenceTimeoutMs: Long = 1500L,
    ): Transcript {
        if (!isAvailable()) throw SttUnavailableException("Pengenalan suara sistem tidak tersedia")
        val started = System.currentTimeMillis()

        // Sesi dibungkus gerbang agar tidak tabrakan dengan transkripsi dikte
        // atau pemantau kata kunci (lihat [RecognizerGate]).
        val (text, engineName) = RecognizerGate.withSession {
            withContext(Dispatchers.Main) {
                listenOnMain(language, onPartial, onLevel, preferOnDevice, timeoutMs, silenceTimeoutMs)
            }
        }

        return Transcript(
            text = text.trim(),
            engine = engineName,
            latencyMs = System.currentTimeMillis() - started,
            language = language,
        )
    }

    private suspend fun listenOnMain(
        language: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        preferOnDevice: Boolean,
        timeoutMs: Long,
        silenceTimeoutMs: Long,
    ): Pair<String, String> {
        val useOnDevice = preferOnDevice &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        val recognizer = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        val engineName = if (useOnDevice) "android-on-device" else "android-system"

        val recognized = try {
            withTimeoutOrNull<String>(timeoutMs) {
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
                        override fun onRmsChanged(rmsdB: Float) {
                            // rmsdB biasanya -2..10 → normalisasi kasar untuk indikator level
                            onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
                        }

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
                            finish { if (cont.isActive) cont.resume(text) }
                        }

                        override fun onError(error: Int) {
                            finish { if (cont.isActive) cont.resumeWithException(mapError(error)) }
                        }
                    })

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        val endpointingSilenceMs = silenceTimeoutMs.coerceIn(400L, 5_000L)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endpointingSilenceMs)
                        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, endpointingSilenceMs)
                    }

                    try {
                        recognizer.startListening(intent)
                    } catch (t: Throwable) {
                        finish { if (cont.isActive) cont.resumeWithException(SttFailedException("startListening gagal", t)) }
                    }
                }
            } ?: throw SttFailedException("Tidak ada ucapan terdeteksi (timeout)")
        } finally {
            runCatching { recognizer.destroy() }
        }
        return recognized to engineName
    }

    private fun mapError(error: Int): Exception = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttUnavailableException("Izin mikrofon ditolak")
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            SttUnavailableException("Bahasa tidak didukung recognizer sistem")
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            SttBusyException("Layanan pengenalan suara sistem sedang sibuk")
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH ->
            SttFailedException("Tidak ada ucapan yang terdeteksi")
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            SttFailedException("Koneksi bermasalah saat pengenalan")
        SpeechRecognizer.ERROR_CLIENT -> SttFailedException("Sesi pengenalan dibatalkan")
        else -> SttFailedException("Sistem error kode $error")
    }
}
