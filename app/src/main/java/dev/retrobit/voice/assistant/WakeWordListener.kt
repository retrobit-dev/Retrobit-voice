package dev.retrobit.voice.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.stt.RecognizerGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pemantau kata kunci ("hai retro") berbasis recognizer.
 *
 * PENTING — ini pendekatan hemat biaya pengembangan, bukan sehemat model
 * wake-word khusus seperti Porcupine/snowboy:
 *   - memakai recognizer on-device bila tersedia (Android 12+),
 *   - memakai jeda antar sesi supaya tidak boros baterai,
 *   - mudah dinonaktifkan (bawaan: mati).
 *
 * Pemantau ini juga harus "mengalah" terhadap dikte: layanan pengenalan
 * Android hanya melayani satu sesi dalam satu waktu, jadi loop yang
 * berjalan bersamaan dengan transkripsi dikte akan membuat keduanya gagal
 * dengan `ERROR_RECOGNIZER_BUSY`. Lihat [RecognizerGate] dan [isYielded].
 *
 * Untuk produksi disarankan mengganti ke model KWS khusus; seluruh kode lain
 * tidak perlu berubah karena hanya bergantung pada [onWake].
 */
class WakeWordListener(
    private val context: Context,
    private val phrase: String,
    private val onWake: (String) -> Unit,
    private val onStateChange: (String) -> Unit = {},
    /**
     * Dipanggil sebelum tiap sesi mendengarkan. Saat mengembalikan true
     * (mis. dikte sedang berjalan), pemantau menahan diri — bukan hanya untuk
     * hemat baterai, tapi karena dua sesi recognizer sekaligus akan saling
     * ditolak layanan pengenalan.
     */
    private val isYielded: () -> Boolean = { false },
) {

    private var job: Job? = null

    @Volatile
    private var running = false

    private val normalizedPhrase: String = normalize(phrase)

    fun start(scope: CoroutineScope, language: String, preferOnDevice: Boolean = true) {
        if (running) return
        running = true
        job = scope.launch {
            onStateChange("Menunggu kata kunci \"$phrase\"")
            while (isActive && running) {
                if (isYielded()) {
                    delay(YIELD_POLL_MS)
                    continue
                }
                val heard = listenOnce(language, preferOnDevice)
                if (!running) break
                if (heard != null) {
                    val normalized = normalize(heard)
                    if (normalized.contains(normalizedPhrase)) {
                        VoiceLog.i("Wake word terdeteksi: $heard")
                        onWake(heard)
                    }
                }
                // Jeda kecil supaya mikrofot tidak "menempel" terus-menerus.
                delay(RESTART_DELAY_MS)
            }
        }
    }

    fun stop() {
        running = false
        val activeJob = job
        job = null
        activeJob?.cancel()
        // Jangan memanggil SpeechRecognizer.cancel/destroy dari thread pemanggil.
        // Coroutine sesi akan dibatalkan di main looper, lalu finally di bawah
        // menghancurkan recognizer di thread yang diwajibkan framework.
        onStateChange("Pemantau kata kunci berhenti")
    }

    /** Hentikan dan tunggu sampai recognizer benar-benar dilepas di main thread. */
    suspend fun stopAndJoin() {
        running = false
        val activeJob = job
        activeJob?.cancelAndJoin()
        if (job === activeJob) job = null
        onStateChange("Pemantau kata kunci berhenti")
    }

    private suspend fun listenOnce(language: String, preferOnDevice: Boolean): String? =
        // Coba ambil gerbang recognizer; bila sedang dipakai (transkripsi dikte
        // atau sesi asisten), lewati giliran tanpa menunggu — kalau menunggu,
        // dikte justru tertunda oleh wake word.
        RecognizerGate.trySession {
            withContext(Dispatchers.Main) {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) return@withContext null
                val useOnDevice = preferOnDevice &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

                val engine = if (useOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }

                try {
                    kotlinx.coroutines.suspendCancellableCoroutine<String?> { cont ->
                        var done = false
                        fun finish(value: String?) {
                            if (done) return
                            done = true
                            if (cont.isActive) cont.resume(value) {}
                        }

                        engine.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {}
                            override fun onBeginningOfSpeech() {}
                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}
                            override fun onEndOfSpeech() {}

                            override fun onPartialResults(partialResults: Bundle?) {
                                val text = partialResults
                                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                    ?.firstOrNull()
                                    .orEmpty()
                                if (text.isNotBlank() && normalize(text).contains(normalizedPhrase)) {
                                    // Kata kunci muncul di hasil parsial → langsung bangunkan.
                                    onStateChange("Kata kunci terdeteksi")
                                    runCatching { engine.stopListening() }
                                    finish(text)
                                }
                            }

                            override fun onResults(results: Bundle?) {
                                finish(
                                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(),
                                )
                            }

                            override fun onError(error: Int) {
                                finish(null)
                            }

                            override fun onEvent(eventType: Int, params: Bundle?) {}
                        })

                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        }
                        runCatching { engine.startListening(intent) }.onFailure { finish(null) }

                    }
                } finally {
                    runCatching { engine.destroy() }
                }
            }
        }

    private fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private companion object {
        const val RESTART_DELAY_MS = 400L

        /** Seberapa sering memeriksa apakah boleh mulai sesi lagi saat mengalah. */
        const val YIELD_POLL_MS = 500L
    }
}
