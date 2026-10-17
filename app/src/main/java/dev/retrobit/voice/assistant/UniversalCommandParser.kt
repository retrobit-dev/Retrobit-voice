package dev.retrobit.voice.assistant

/**
 * Perintah percakapan yang selalu tersedia, terlepas dari layar/aplikasi aktif.
 * "Stop/berhenti" sengaja tidak ditangkap di sini agar tetap bisa mengendalikan
 * media sesuai konteks.
 */
object UniversalCommandParser {

    enum class Command { CANCEL_PENDING, REPEAT_LAST_REPLY }

    private val cancelPhrases = setOf(
        "batal", "batalkan", "batalin", "tidak jadi", "enggak jadi", "nggak jadi", "gak jadi", "ga jadi",
        "jangan jadi", "cancel", "cancel it",
    )
    private val repeatPhrases = setOf(
        "ulang", "ulangi", "ulang lagi", "ulangi lagi", "ulang jawaban", "ulangi jawaban",
        "ulangi jawaban terakhir", "repeat", "repeat that", "say that again", "say it again",
    )

    fun parse(text: String): Command? {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when (normalized) {
            in cancelPhrases -> Command.CANCEL_PENDING
            in repeatPhrases -> Command.REPEAT_LAST_REPLY
            else -> null
        }
    }
}
