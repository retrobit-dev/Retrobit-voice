package dev.retrobit.voice.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.retrobit.voice.core.VoiceLog
import java.util.Locale

/**
 * Pembaca jawaban asisten (text-to-speech) memakai mesin TTS bawaan Android —
 * gratis, offline untuk banyak bahasa, dan tidak perlu izin tambahan.
 */
class TtsSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingLanguage: String? = null

    fun init() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) VoiceLog.w("TTS tidak siap (status $status)")
            pendingLanguage?.let { applyLanguage(it) }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
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

    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }
}
