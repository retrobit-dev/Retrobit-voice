package dev.retrobit.voice.assistant

/** Heuristik lokal untuk membatasi snapshot layar hanya pada permintaan kontekstual. */
object ScreenContextRequest {

    private val phrases = listOf(
        "balas", "balasan", "reply", "respond", "jawab pesan", "jawab chat",
        "pesan terakhir", "pesan terbaru", "last message", "latest message",
        "chat ini", "pesan ini", "baca chat", "baca pesan", "baca notifikasi", "bacakan",
        "baca layar", "isi chat", "isi pesan", "teks di layar", "apa yang tertulis", "apa yang ada di layar",
        "apa yang terlihat", "apa ini", "yang tertulis di layar", "yang ada di layar", "konteks layar", "baca whatsapp",
        "what did they say", "what does this say",
        "what is on the screen", "read this chat", "read the screen", "read messages",
        "reply to this", "di aplikasi ini", "yang sedang terbuka", "yang tampil di layar",
    )

    /** Awalan perintah media; dipisahkan agar kalimat umum yang sekadar menyebut kata “play” tidak membaca layar. */
    private val mediaCommandPrefixes = listOf(
        "mainkan", "putar", "jeda", "hentikan sementara", "pause", "lanjutkan", "teruskan",
        "berhenti", "hentikan", "play", "resume", "stop", "lagu berikutnya", "video berikutnya",
        "berikutnya", "selanjutnya", "next track", "next video", "next", "lagu sebelumnya",
        "video sebelumnya", "sebelumnya", "previous track", "previous video", "previous",
    )

    private val readPhrases = listOf(
        "baca chat", "baca pesan", "baca notifikasi", "bacakan", "baca layar", "baca teks",
        "baca whatsapp", "teks di layar", "apa yang tertulis", "apa yang ada di layar", "apa yang terlihat", "apa ini", "yang tertulis di layar",
        "what does this say", "what did they say", "what is on the screen", "what is shown", "read this chat",
        "read the screen", "read messages", "read last message",
    )

    fun isRequested(text: String): Boolean = normalized(text).let { normalized ->
        phrases.any(normalized::containsPhrase) || isMediaCommand(normalized)
    }

    fun isReadRequested(text: String): Boolean = normalized(text).let { normalized ->
        readPhrases.any(normalized::containsPhrase)
    }

    private fun isMediaCommand(normalizedText: String): Boolean {
        var command = normalizedText
        val leadIns = listOf("hey retro", "hai retro", "tolong", "please", "hey", "retro")
        var changed = true
        while (changed) {
            changed = false
            val lead = leadIns.firstOrNull { command.startsWith("$it ") }
            if (lead != null) {
                command = command.removePrefix(lead).trimStart()
                changed = true
            }
        }
        return mediaCommandPrefixes.any { command == it || command.startsWith("$it ") }
    }

    private fun String.containsPhrase(phrase: String): Boolean = " $this ".contains(" $phrase ")

    private fun normalized(text: String): String = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
