package dev.retrobit.voice.assistant

/** Heuristik lokal untuk membatasi snapshot layar hanya pada permintaan kontekstual. */
object ScreenContextRequest {

    private val phrases = listOf(
        "balas", "balasan", "reply", "respond", "jawab pesan", "jawab chat",
        "pesan terakhir", "pesan terbaru", "last message", "latest message",
        "chat ini", "pesan ini", "baca chat", "baca pesan", "baca notifikasi", "bacakan",
        "baca layar", "isi chat", "isi pesan", "teks di layar", "apa yang tertulis",
        "yang tertulis di layar", "yang ada di layar", "konteks layar", "baca whatsapp",
        "pesan whatsapp", "chat whatsapp", "what did they say", "what does this say",
        "what is on the screen", "read this chat", "read the screen", "read messages",
        "reply to this",
    )

    private val readPhrases = listOf(
        "baca chat", "baca pesan", "baca notifikasi", "bacakan", "baca layar", "baca teks",
        "baca whatsapp", "teks di layar", "apa yang tertulis", "yang tertulis di layar",
        "what does this say", "what did they say", "what is on the screen", "read this chat",
        "read the screen", "read messages", "read last message",
    )

    fun isRequested(text: String): Boolean = normalized(text).let { normalized ->
        phrases.any { phrase -> normalized.contains(phrase) }
    }

    fun isReadRequested(text: String): Boolean = normalized(text).let { normalized ->
        readPhrases.any { phrase -> normalized.contains(phrase) }
    }

    private fun normalized(text: String): String = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
