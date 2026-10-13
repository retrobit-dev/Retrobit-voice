package dev.retrobit.voice.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Pembaca jawaban asisten (text-to-speech) memakai mesin TTS bawaan Android —
 * gratis, offline untuk banyak bahasa, dan tidak perlu izin tambahan.
 */
class TtsSpeaker(private val context: Context) {

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var pendingLanguage: String? = null

    @Volatile
    private var readiness = CompletableDeferred<Boolean>().apply { complete(false) }

    private val utteranceWaiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    fun init() {
        if (tts != null) return
        readiness = CompletableDeferred()
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                VoiceLog.w("TTS tidak siap (status $status)")
            } else {
                pendingLanguage?.let { applyLanguage(it) }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        utteranceId?.let { utteranceWaiters.remove(it)?.complete(true) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        utteranceId?.let { utteranceWaiters.remove(it)?.complete(false) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        utteranceId?.let { utteranceWaiters.remove(it)?.complete(false) }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        utteranceId?.let { utteranceWaiters.remove(it)?.complete(false) }
                    }
                })
            }
            readiness.complete(ready)
        }
    }

    private fun applyLanguage(languageTag: String) {
        val locale = Locale.forLanguageTag(languageTag)
        val result = tts?.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            VoiceLog.w("TTS tidak punya paket bahasa $languageTag, memakai locale perangkat")
            tts?.setLanguage(Locale.getDefault())
        }
    }

    fun speak(text: String, languageTag: String, flush: Boolean = true) {
        if (text.isBlank()) return
        init()
        val engine = tts ?: return
        if (!ready) {
            pendingLanguage = languageTag
            return
        }
        applyLanguage(languageTag)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text.take(600), mode, null, "retrobit-" + System.currentTimeMillis())
    }

    /**
     * Ucapkan dan tunggu callback TTS selesai. Dipakai sebelum mulai STT agar prompt
     * "Siap, silakan bicara" tidak tumpang tindih dengan mikrofon.
     */
    suspend fun speakAndWait(text: String, languageTag: String, flush: Boolean = true): Boolean {
        if (text.isBlank()) return true
        withContext(Dispatchers.Main.immediate) { init() }
        val isReady = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { readiness.await() } ?: false
        if (!isReady) return false

        val id = "retrobit-wait-${System.nanoTime()}"
        val completed = CompletableDeferred<Boolean>()
        utteranceWaiters[id] = completed
        val accepted = withContext(Dispatchers.Main.immediate) {
            val engine = tts ?: return@withContext false
            applyLanguage(languageTag)
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(text.take(600), mode, null, id) == TextToSpeech.SUCCESS
        }
        if (!accepted) {
            utteranceWaiters.remove(id)
            return false
        }

        val timeoutMs = (text.length * 180L + 2_000L).coerceIn(5_000L, 30_000L)
        val result = withTimeoutOrNull(timeoutMs) { completed.await() } ?: false
        utteranceWaiters.remove(id)
        return result
    }

    fun stop() {
        runCatching { tts?.stop() }
        utteranceWaiters.values.forEach { it.complete(false) }
        utteranceWaiters.clear()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
        readiness = CompletableDeferred<Boolean>().apply { complete(false) }
    }

    private companion object {
        const val TTS_INIT_TIMEOUT_MS = 4_000L
    }
}
