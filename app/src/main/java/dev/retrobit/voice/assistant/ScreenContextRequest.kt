package dev.retrobit.voice.assistant

/** Heuristik lokal untuk membatasi snapshot layar hanya pada permintaan kontekstual. */
object ScreenContextRequest {

    private val phrases = listOf(
        "balas", "balasan", "reply", "respond", "jawab pesan", "jawab chat",
        "pesan terakhir", "pesan terbaru", "last message", "latest message",
        "chat ini", "pesan ini", "baca chat", "baca pesan", "isi chat", "isi pesan",
        "teks di layar", "yang tertulis di layar", "yang ada di layar", "konteks layar",
        "what did they say", "what does this say", "read this chat", "reply to this",
    )

    fun isRequested(text: String): Boolean {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return phrases.any { phrase -> normalized.contains(phrase) }
    }
}
