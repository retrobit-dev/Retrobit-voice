package dev.retrobit.voice.assistant

/** Parser jawaban ya/tidak untuk pesan maupun aksi telepon yang menunggu persetujuan. */
object ConfirmationParser {

    enum class Decision { YES, NO, UNCLEAR }

    private val positiveAnswers = setOf(
        "ya", "iya", "yup", "oke", "ok", "setuju", "boleh", "benar", "betul", "lanjut", "lanjutkan",
        "kirim", "kirimkan", "silakan kirim", "ya kirim", "iya kirim", "oke kirim", "ok kirim",
        "buka draf", "siapkan draf", "ya buka draf", "iya buka draf", "ya siapkan draf", "iya siapkan draf",
        "ya silakan", "iya silakan", "ya lanjut", "iya lanjut", "yes", "send", "confirm",
    )
    private val negativeAnswers = setOf(
        "tidak", "enggak", "nggak", "gak", "ga", "jangan", "batal", "batalkan", "batalin", "cancel", "cancel it",
        "tidak jadi", "nggak jadi", "enggak jadi", "gak jadi", "ga jadi", "jangan jadi",
        "jangan kirim", "tidak kirim", "batal kirim", "berhenti", "stop", "no",
    )

    fun parse(text: String): Decision {
        val normalized = text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized in negativeAnswers) return Decision.NO
        if (normalized in positiveAnswers) return Decision.YES

        // Jangan menerima kalimat campuran yang mengandung penolakan sebagai setuju.
        val words = normalized.split(' ').filter(String::isNotBlank)
        if (words.any {
                it in setOf("tidak", "nggak", "enggak", "gak", "ga", "jangan", "batal", "batalin", "cancel", "berhenti", "stop", "no")
            }
        ) {
            return Decision.NO
        }
        if (words.firstOrNull()?.let { it in setOf("ya", "iya", "oke", "ok", "setuju", "boleh", "yes") } == true) {
            if (words.any { it in setOf("kirim", "kirimkan", "lanjut", "lanjutkan", "silakan", "send", "buka", "siapkan", "draf") }) {
                return Decision.YES
            }
        }
        return Decision.UNCLEAR
    }
}
