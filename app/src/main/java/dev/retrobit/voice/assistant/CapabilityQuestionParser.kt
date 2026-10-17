package dev.retrobit.voice.assistant

/** Mendeteksi pertanyaan kemampuan agar tidak keliru mengeksekusi perintah yang baru ditanyakan. */
object CapabilityQuestionParser {

    enum class Topic { WHATSAPP_OVERVIEW, WHATSAPP_CONTACTS, WHATSAPP_CHAT, WHATSAPP_CALL, GENERAL }

    data class Query(val requestedCommand: String, val topic: Topic)

    private val prefixes = listOf(
        "apakah asisten juga bisa", "apakah asisten juga dapat", "apakah asisten bisa", "apakah asisten dapat",
        "apakah kamu bisa", "apakah kamu dapat", "apakah bisa", "apa kamu bisa", "apa kamu dapat",
        "bisakah kamu", "bisakah", "dapatkah kamu", "dapatkah", "mampukah kamu", "bisa kamu", "bisa",
        "kamu bisa", "kamu dapat", "can you", "could you", "would you be able to", "are you able to",
        "can the assistant", "does retrobit",
    ).sortedByDescending(String::length)

    fun parse(text: String): Query? {
        var normalized = normalize(text)
        val politeLead = listOf("tolong ", "please ").firstOrNull { normalized.startsWith(it) }
        if (politeLead != null) normalized = normalized.removePrefix(politeLead).trimStart()

        val prefix = prefixes.firstOrNull { normalized == it || normalized.startsWith("$it ") } ?: return null
        val requested = normalized.removePrefix(prefix).trim().trim('?', '.', '!', ',', ' ')
        val wholeQuestion = normalize(text)
        val hasWhatsApp = wholeQuestion.contains("whatsapp")
        val hasContact = wholeQuestion.containsAny("kontak", "contact")
        val hasChat = wholeQuestion.containsAny("chat", "pesan", "message", "mengirim", "kirim")
        val hasCall = wholeQuestion.containsAny("telepon", "telpon", "panggil", "call", "phone")
        val topic = when {
            hasWhatsApp && hasContact && hasChat && hasCall -> Topic.WHATSAPP_OVERVIEW
            hasWhatsApp &&
                hasContact &&
                wholeQuestion.containsAny("daftar", "list", "melihat", "lihat", "read", "see") -> Topic.WHATSAPP_CONTACTS
            wholeQuestion.contains("whatsapp") &&
                wholeQuestion.containsAny("telepon", "telpon", "panggil", "call", "phone") -> Topic.WHATSAPP_CALL
            wholeQuestion.contains("whatsapp") &&
                wholeQuestion.containsAny("chat", "pesan", "message", "mengirim", "kirim") -> Topic.WHATSAPP_CHAT
            else -> Topic.GENERAL
        }
        return Query(requestedCommand = requested, topic = topic)
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.containsAny(vararg terms: String): Boolean = terms.any(::contains)
}
