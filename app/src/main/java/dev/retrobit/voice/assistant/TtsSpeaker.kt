package dev.retrobit.voice.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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

    data class VoiceOption(
        val name: String,
        val localeTag: String,
        val quality: Int,
        val requiresNetwork: Boolean,
    )

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var pendingLanguage: String? = null

    @Volatile
    private var pendingVoiceName: String? = null

    @Volatile
    private var pendingPitch: Float = DEFAULT_PITCH

    @Volatile
    private var pendingSpeechRate: Float = DEFAULT_SPEECH_RATE

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
                pendingLanguage?.let {
                    applyVoiceSettings(it, pendingVoiceName, pendingPitch, pendingSpeechRate)
                }
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

    private fun applyVoiceSettings(
        languageTag: String,
        voiceName: String?,
        pitch: Float,
        speechRate: Float,
    ) {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(languageTag)
        val languageResult = engine.setLanguage(locale)
        val activeLocale = if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            VoiceLog.w("TTS tidak punya paket bahasa $languageTag, memakai locale perangkat")
            Locale.getDefault().also(engine::setLanguage)
        } else {
            locale
        }

        if (!voiceName.isNullOrBlank()) {
            val selectedVoice = engine.voices?.firstOrNull {
                it.name == voiceName && it.locale.language.equals(activeLocale.language, ignoreCase = true)
            }
            if (selectedVoice != null) {
                engine.setVoice(selectedVoice)
            } else {
                VoiceLog.w("Suara TTS '$voiceName' tidak tersedia untuk bahasa $languageTag; memakai suara bawaan")
            }
        }
        engine.setPitch(pitch.coerceIn(MIN_PITCH, MAX_PITCH))
        engine.setSpeechRate(speechRate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE))
    }

    /** Daftar suara yang benar-benar disediakan mesin TTS aktif untuk bahasa pilihan. */
    suspend fun availableVoices(languageTag: String): List<VoiceOption> {
        withContext(Dispatchers.Main.immediate) { init() }
        val initialized = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { readiness.await() } ?: false
        if (!initialized) return emptyList()

        val requestedLocale = Locale.forLanguageTag(languageTag)
        return withContext(Dispatchers.Main.immediate) {
            tts?.voices.orEmpty()
                .filter { it.locale.language.equals(requestedLocale.language, ignoreCase = true) }
                .sortedWith(
                    compareBy<Voice>(
                        { it.locale.country != requestedLocale.country },
                        { it.isNetworkConnectionRequired },
                        { -it.quality },
                        { it.name },
                    ),
                )
                .map { voice ->
                    VoiceOption(
                        name = voice.name,
                        localeTag = voice.locale.toLanguageTag(),
                        quality = voice.quality,
                        requiresNetwork = voice.isNetworkConnectionRequired,
                    )
                }
        }
    }

    fun speak(
        text: String,
        languageTag: String,
        flush: Boolean = true,
        voiceName: String? = null,
        pitch: Float = DEFAULT_PITCH,
        speechRate: Float = DEFAULT_SPEECH_RATE,
    ): Boolean {
        if (text.isBlank()) return true
        init()
        val engine = tts ?: return false
        if (!ready) {
            pendingLanguage = languageTag
            pendingVoiceName = voiceName
            pendingPitch = pitch
            pendingSpeechRate = speechRate
            return false
        }
        applyVoiceSettings(languageTag, voiceName, pitch, speechRate)
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return engine.speak(text.take(600), mode, null, "retrobit-" + System.currentTimeMillis()) == TextToSpeech.SUCCESS
    }

    /**
     * Ucapkan dan tunggu callback TTS selesai. Dipakai sebelum mulai STT agar prompt
     * "Siap, silakan bicara" tidak tumpang tindih dengan mikrofon.
     */
    suspend fun speakAndWait(
        text: String,
        languageTag: String,
        flush: Boolean = true,
        voiceName: String? = null,
        pitch: Float = DEFAULT_PITCH,
        speechRate: Float = DEFAULT_SPEECH_RATE,
    ): Boolean {
        if (text.isBlank()) return true
        withContext(Dispatchers.Main.immediate) { init() }
        val isReady = withTimeoutOrNull(TTS_INIT_TIMEOUT_MS) { readiness.await() } ?: false
        if (!isReady) return false

        val id = "retrobit-wait-${System.nanoTime()}"
        val completed = CompletableDeferred<Boolean>()
        utteranceWaiters[id] = completed
        val accepted = withContext(Dispatchers.Main.immediate) {
            val engine = tts ?: return@withContext false
            applyVoiceSettings(languageTag, voiceName, pitch, speechRate)
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
        const val DEFAULT_PITCH = 1f
        const val DEFAULT_SPEECH_RATE = 1f
        const val MIN_PITCH = 0.6f
        const val MAX_PITCH = 1.6f
        const val MIN_SPEECH_RATE = 0.6f
        const val MAX_SPEECH_RATE = 1.6f
    }
}
