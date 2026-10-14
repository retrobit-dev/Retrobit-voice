package dev.retrobit.voice.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "retrobit_settings")

/** Pilihan mesin STT. */
enum class SttEngineChoice { AUTO, ON_DEVICE, CLOUD, SYSTEM_LIVE }

/** Penyedia STT cloud. */
enum class CloudSttProvider { OPENAI, GROQ, DEEPGRAM }

/** Penyedia pemroses bahasa (LLM). */
enum class LlmProvider { OFF, OPENAI, GEMINI, OLLAMA }

/** Mode pembersihan teks hasil dikte. */
enum class CleanupMode { OFF, LOCAL, AI }

/** Cara menempelkan teks ke aplikasi tujuan. */
enum class InsertMethod { AUTO, IME, ACCESSIBILITY }

/** Cara memicu dikte. */
enum class DictationTrigger { HOLD_TO_TALK, HANDS_FREE, HYBRID }

/** Seluruh preferensi aplikasi dalam satu objek tak berubah (immutable). */
data class AppSettings(
    // --- Mesin dikte ---
    val sttEngine: SttEngineChoice = SttEngineChoice.AUTO,
    val cloudSttProvider: CloudSttProvider = CloudSttProvider.OPENAI,
    val cloudSttApiKey: String = "",
    val cloudSttBaseUrl: String = "",
    val cloudSttModel: String = "",
    val dictationLanguage: String = "id-ID",
    val cleanupMode: CleanupMode = CleanupMode.LOCAL,
    val autoPunctuation: Boolean = true,
    val autoCapitalize: Boolean = true,
    val insertMethod: InsertMethod = InsertMethod.AUTO,
    val dictationTrigger: DictationTrigger = DictationTrigger.HOLD_TO_TALK,
    /**
     * Tombol volume dipakai untuk memicu dikte. Butuh layanan aksesibilitas aktif
     * karena hanya jalur itu yang bisa membaca peristiwa tombol volume.
     */
    val volumeKeyTriggerEnabled: Boolean = false,
    /** Tombol volume ditahan untuk membuka sesi perintah suara asisten. */
    val volumeKeyAssistantEnabled: Boolean = false,
    val silenceTimeoutMs: Int = 1600,
    val keepScreenOnWhileDictating: Boolean = false,

    // --- Asisten ---
    val llmProvider: LlmProvider = LlmProvider.OFF,
    val llmApiKey: String = "",
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    val wakeWordEnabled: Boolean = false,
    val wakeWordPhrase: String = "hai retro",
    val confirmBeforeActions: Boolean = true,
    /** Baca teks layar satu kali saat pengguna meminta asisten (bawaan: mati). */
    val screenContextEnabled: Boolean = false,
    val speakReplies: Boolean = true,
    /** Nama voice dari mesin TTS Android; kosong memakai suara bawaan untuk bahasa. */
    val ttsVoiceName: String = "",
    val ttsPitch: Float = 1f,
    val ttsSpeechRate: Float = 1f,
    val assistantLanguage: String = "id-ID",

    // --- Tampilan & data ---
    val overlayBubbleEnabled: Boolean = false,
    val saveHistory: Boolean = true,
    /** true = kertas terang (e-paper sesungguhnya), false = panel gelap cyberdeck. */
    val epaperLightMode: Boolean = false,
    val lastOnboardingStep: Int = 0,
) {
    /** Apakah STT cloud siap dipakai (ada API key). */
    val cloudSttReady: Boolean get() = cloudSttApiKey.isNotBlank()

    /** Apakah LLM cloud siap dipakai. */
    val llmReady: Boolean get() = llmProvider != LlmProvider.OFF && (llmProvider == LlmProvider.OLLAMA || llmApiKey.isNotBlank())
}

/**
 * Sumber tunggal preferensi. API key disimpan terenkripsi lewat [SecretStore].
 */
class SettingsRepository(private val context: Context) {

    private object K {
        val sttEngine = stringPreferencesKey("stt_engine")
        val cloudSttProvider = stringPreferencesKey("cloud_stt_provider")
        val cloudSttApiKey = stringPreferencesKey("cloud_stt_api_key")
        val cloudSttBaseUrl = stringPreferencesKey("cloud_stt_base_url")
        val cloudSttModel = stringPreferencesKey("cloud_stt_model")
        val dictationLanguage = stringPreferencesKey("dictation_language")
        val cleanupMode = stringPreferencesKey("cleanup_mode")
        val autoPunctuation = booleanPreferencesKey("auto_punctuation")
        val autoCapitalize = booleanPreferencesKey("auto_capitalize")
        val insertMethod = stringPreferencesKey("insert_method")
        val dictationTrigger = stringPreferencesKey("dictation_trigger")
        val volumeKeyTrigger = booleanPreferencesKey("volume_key_trigger")
        val volumeKeyAssistant = booleanPreferencesKey("volume_key_assistant")
        val silenceTimeoutMs = intPreferencesKey("silence_timeout_ms")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")

        val llmProvider = stringPreferencesKey("llm_provider")
        val llmApiKey = stringPreferencesKey("llm_api_key")
        val llmBaseUrl = stringPreferencesKey("llm_base_url")
        val llmModel = stringPreferencesKey("llm_model")
        val wakeWordEnabled = booleanPreferencesKey("wake_word_enabled")
        val wakeWordPhrase = stringPreferencesKey("wake_word_phrase")
        val confirmBeforeActions = booleanPreferencesKey("confirm_actions")
        val screenContext = booleanPreferencesKey("screen_context_enabled")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val ttsVoiceName = stringPreferencesKey("tts_voice_name")
        val ttsPitch = floatPreferencesKey("tts_pitch")
        val ttsSpeechRate = floatPreferencesKey("tts_speech_rate")
        val assistantLanguage = stringPreferencesKey("assistant_language")

        val overlayBubble = booleanPreferencesKey("overlay_bubble")
        val saveHistory = booleanPreferencesKey("save_history")
        val epaperLight = booleanPreferencesKey("epaper_light")
        val onboardingStep = intPreferencesKey("onboarding_step")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs[K.sttEngine] = updated.sttEngine.name
            prefs[K.cloudSttProvider] = updated.cloudSttProvider.name
            prefs[K.cloudSttApiKey] = SecretStore.encrypt(ApiKeys.sanitize(updated.cloudSttApiKey))
            prefs[K.cloudSttBaseUrl] = updated.cloudSttBaseUrl
            prefs[K.cloudSttModel] = updated.cloudSttModel
            prefs[K.dictationLanguage] = updated.dictationLanguage
            prefs[K.cleanupMode] = updated.cleanupMode.name
            prefs[K.autoPunctuation] = updated.autoPunctuation
            prefs[K.autoCapitalize] = updated.autoCapitalize
            prefs[K.insertMethod] = updated.insertMethod.name
            prefs[K.dictationTrigger] = updated.dictationTrigger.name
            prefs[K.volumeKeyTrigger] = updated.volumeKeyTriggerEnabled
            prefs[K.volumeKeyAssistant] = updated.volumeKeyAssistantEnabled
            prefs[K.silenceTimeoutMs] = updated.silenceTimeoutMs
            prefs[K.keepScreenOn] = updated.keepScreenOnWhileDictating

            prefs[K.llmProvider] = updated.llmProvider.name
            prefs[K.llmApiKey] = SecretStore.encrypt(ApiKeys.sanitize(updated.llmApiKey))
            prefs[K.llmBaseUrl] = updated.llmBaseUrl
            prefs[K.llmModel] = updated.llmModel
            prefs[K.wakeWordEnabled] = updated.wakeWordEnabled
            prefs[K.wakeWordPhrase] = updated.wakeWordPhrase
            prefs[K.confirmBeforeActions] = updated.confirmBeforeActions
            prefs[K.screenContext] = updated.screenContextEnabled
            prefs[K.speakReplies] = updated.speakReplies
            prefs[K.ttsVoiceName] = updated.ttsVoiceName
            prefs[K.ttsPitch] = updated.ttsPitch.coerceIn(0.6f, 1.6f)
            prefs[K.ttsSpeechRate] = updated.ttsSpeechRate.coerceIn(0.6f, 1.6f)
            prefs[K.assistantLanguage] = updated.assistantLanguage

            prefs[K.overlayBubble] = updated.overlayBubbleEnabled
            prefs[K.saveHistory] = updated.saveHistory
            prefs[K.epaperLight] = updated.epaperLightMode
            prefs[K.onboardingStep] = updated.lastOnboardingStep
        }
    }

    suspend fun clearHistory() {
        HistoryStore(context).clear()
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            sttEngine = enumOr(this[K.sttEngine], defaults.sttEngine),
            cloudSttProvider = enumOr(this[K.cloudSttProvider], defaults.cloudSttProvider),
            cloudSttApiKey = ApiKeys.sanitize(SecretStore.decrypt(this[K.cloudSttApiKey])),
            cloudSttBaseUrl = this[K.cloudSttBaseUrl] ?: defaults.cloudSttBaseUrl,
            cloudSttModel = this[K.cloudSttModel] ?: defaults.cloudSttModel,
            dictationLanguage = this[K.dictationLanguage] ?: defaults.dictationLanguage,
            cleanupMode = enumOr(this[K.cleanupMode], defaults.cleanupMode),
            autoPunctuation = this[K.autoPunctuation] ?: defaults.autoPunctuation,
            autoCapitalize = this[K.autoCapitalize] ?: defaults.autoCapitalize,
            insertMethod = enumOr(this[K.insertMethod], defaults.insertMethod),
            dictationTrigger = enumOr(this[K.dictationTrigger], defaults.dictationTrigger),
            volumeKeyTriggerEnabled = this[K.volumeKeyTrigger] ?: defaults.volumeKeyTriggerEnabled,
            volumeKeyAssistantEnabled = this[K.volumeKeyAssistant] ?: defaults.volumeKeyAssistantEnabled,
            silenceTimeoutMs = this[K.silenceTimeoutMs] ?: defaults.silenceTimeoutMs,
            keepScreenOnWhileDictating = this[K.keepScreenOn] ?: defaults.keepScreenOnWhileDictating,
            llmProvider = enumOr(this[K.llmProvider], defaults.llmProvider),
            llmApiKey = ApiKeys.sanitize(SecretStore.decrypt(this[K.llmApiKey])),
            llmBaseUrl = this[K.llmBaseUrl] ?: defaults.llmBaseUrl,
            llmModel = this[K.llmModel] ?: defaults.llmModel,
            wakeWordEnabled = this[K.wakeWordEnabled] ?: defaults.wakeWordEnabled,
            wakeWordPhrase = this[K.wakeWordPhrase] ?: defaults.wakeWordPhrase,
            confirmBeforeActions = this[K.confirmBeforeActions] ?: defaults.confirmBeforeActions,
            screenContextEnabled = this[K.screenContext] ?: defaults.screenContextEnabled,
            speakReplies = this[K.speakReplies] ?: defaults.speakReplies,
            ttsVoiceName = this[K.ttsVoiceName] ?: defaults.ttsVoiceName,
            ttsPitch = (this[K.ttsPitch] ?: defaults.ttsPitch).coerceIn(0.6f, 1.6f),
            ttsSpeechRate = (this[K.ttsSpeechRate] ?: defaults.ttsSpeechRate).coerceIn(0.6f, 1.6f),
            assistantLanguage = this[K.assistantLanguage] ?: defaults.assistantLanguage,
            overlayBubbleEnabled = this[K.overlayBubble] ?: defaults.overlayBubbleEnabled,
            saveHistory = this[K.saveHistory] ?: defaults.saveHistory,
            epaperLightMode = this[K.epaperLight] ?: defaults.epaperLightMode,
            lastOnboardingStep = this[K.onboardingStep] ?: defaults.lastOnboardingStep,
        )
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    companion object {
        /** Nilai bawaan per penyedia cloud, dipakai di UI pengaturan. */
        fun defaultBaseUrl(provider: CloudSttProvider): String = when (provider) {
            CloudSttProvider.OPENAI -> "https://api.openai.com"
            CloudSttProvider.GROQ -> "https://api.groq.com/openai"
            CloudSttProvider.DEEPGRAM -> "https://api.deepgram.com"
        }

        fun defaultModel(provider: CloudSttProvider): String = when (provider) {
            CloudSttProvider.OPENAI -> "gpt-4o-mini-transcribe"
            CloudSttProvider.GROQ -> "whisper-large-v3-turbo"
            CloudSttProvider.DEEPGRAM -> "nova-2"
        }

        fun defaultLlmBaseUrl(provider: LlmProvider): String = when (provider) {
            LlmProvider.OPENAI -> "https://api.openai.com"
            LlmProvider.GEMINI -> "https://generativelanguage.googleapis.com"
            LlmProvider.OLLAMA -> "http://192.168.1.10:11434"
            LlmProvider.OFF -> ""
        }

        fun defaultLlmModel(provider: LlmProvider): String = when (provider) {
            LlmProvider.OPENAI -> "gpt-4o-mini"
            LlmProvider.GEMINI -> "gemini-2.0-flash"
            LlmProvider.OLLAMA -> "llama3.1"
            LlmProvider.OFF -> ""
        }
    }
}
