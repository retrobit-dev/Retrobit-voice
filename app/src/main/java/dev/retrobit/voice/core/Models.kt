package dev.retrobit.voice.core

/**
 * Model dasar yang dipakai bersama oleh mode Dikte dan mode Asisten.
 */

/** Hasil transkripsi dari salah satu mesin STT. */
data class Transcript(
    val text: String,
    /** id mesin yang menghasilkan: "on-device", "cloud:openai", "android" */
    val engine: String,
    val latencyMs: Long = 0L,
    val language: String = "id",
    val confidence: Float = -1f,
)

/** Contoh audio hasil rekaman, siap dikirim ke mesin STT mana pun. */
data class PcmRecording(
    val wavFile: java.io.File,
    val sampleRate: Int,
    val durationMs: Long,
    val peakRms: Float,
)

/** Status perekaman yang dibagikan ke UI. */
enum class CaptureState { IDLE, REQUESTING_PERMISSION, LISTENING, TRANSCRIBING, DONE, ERROR }

/** Satu langkah perintah yang dikenali asisten. */
sealed interface AssistantAction {
    data class OpenApp(val appQuery: String) : AssistantAction
    data class WebSearch(val query: String, val site: String? = null) : AssistantAction
    data class Dial(val target: String, val contactName: String? = null) : AssistantAction
    data class CallContact(val name: String) : AssistantAction
    data class SetAlarm(val hour: Int, val minute: Int, val label: String) : AssistantAction
    data class SetTimer(val seconds: Int, val label: String) : AssistantAction
    data class OpenSettingsPanel(val panel: String) : AssistantAction
    data class SendMessage(val contactName: String, val body: String) : AssistantAction
    data class SaveNote(val body: String) : AssistantAction
    data class SetTorch(val on: Boolean) : AssistantAction
    /** [relative] = true berarti [percent] sebagai langkah (+1 / -1), bukan persentase. */
    data class SetVolume(val percent: Int, val relative: Boolean = false) : AssistantAction
    data class SetBrightness(val percent: Int, val relative: Boolean = false) : AssistantAction
    data class SetDnd(val enabled: Boolean) : AssistantAction
    data class SetBluetooth(val enabled: Boolean) : AssistantAction
    data object AnswerCall : AssistantAction
    data object GetCurrentTime : AssistantAction
    data object GetCurrentDate : AssistantAction
    data object GetBatteryStatus : AssistantAction
    data class GetWeather(val location: String?) : AssistantAction
    /** Insert exact text into the focused field at its active cursor/selection. */
    data class TypeText(val text: String) : AssistantAction
    data object StartDictation : AssistantAction
    data class OpenWhatsAppChat(val contactName: String, val preparingCall: Boolean = false) : AssistantAction
    data class ControlMedia(val command: MediaCommand) : AssistantAction
    data object ShowHelp : AssistantAction
    data class Unknown(val rawText: String) : AssistantAction
}

/** Perintah transport media yang dikirim ke sesi pemutaran Android aktif. */
enum class MediaCommand { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

/** Satu giliran percakapan dengan asisten. */
data class AssistantTurn(
    val userText: String,
    val action: AssistantAction?,
    val reply: String,
    val source: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Ringkasan perangkat/asisten yang tidak bisa berubah (untuk UI & diagnosa). */
val supportedDictationLanguages: List<Pair<String, String>> = listOf(
    "id-ID" to "Bahasa Indonesia",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "ms-MY" to "Bahasa Melayu",
    "jv-ID" to "Basa Jawa",
    "su-ID" to "Basa Sunda",
    "zh-CN" to "中文 (简体)",
    "ja-JP" to "日本語",
    "ar-SA" to "العربية",
    "es-ES" to "Español",
)
