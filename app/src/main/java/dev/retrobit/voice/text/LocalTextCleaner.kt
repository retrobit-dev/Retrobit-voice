package dev.retrobit.voice.text

/**
 * "AI cleanup" versi lokal — tanpa jaringan, tanpa API key.
 *
 * Tugasnya:
 *  1. buang kata pengisi (filler) khas ucapan,
 *  2. ubah perintah tanda baca yang diucapkan menjadi tanda baca,
 *  3. rapikan spasi, huruf besar di awal kalimat, dan tanda baca akhir.
 *
 * Untuk pembersihan tingkat lanjut (memperbaiki kalimat berantakan, mengubah
 * gaya bahasa) aplikasi memakai LLM — lihat [dev.retrobit.voice.ai.LlmCleaner].
 */
object LocalTextCleaner {

    data class Options(
        val autoPunctuation: Boolean = true,
        val autoCapitalize: Boolean = true,
        /** Kamus pengganti buatan pengguna, mis. "retro bit" -> "Retrobit". */
        val replacements: Map<String, String> = emptyMap(),
    )

    /** Filler yang aman dibuang tanpa mengubah makna. */
    private val fillers = setOf(
        "eee", "ee", "eee...", "ehh", "eh", "em", "emm", "emmm", "hmm", "hmmm", "mmm",
        "aaa", "aa", "anuu", "anu", "uh", "uhh", "um", "umm", "err", "errr", "hehe",
        "yaa", "hmm hmm",
    )

    /** Perintah tanda baca yang diucapkan (id + en). */
    private val punctuationCommands = mapOf(
        "titik" to ".", "titik koma" to ";", "koma" to ",", "tanda tanya" to "?",
        "tanda seru" to "!", "tanda kurung buka" to "(", "tanda kurung tutup" to ")",
        "kutip buka" to "\"", "kutip tutup" to "\"", "titik dua" to ":",
        "baris baru" to "\n", "garis baru" to "\n", "enter" to "\n", "paragraf baru" to "\n\n",
        "period" to ".", "full stop" to ".", "comma" to ",", "question mark" to "?",
        "exclamation mark" to "!", "new line" to "\n", "newline" to "\n", "colon" to ":",
        "semicolon" to ";", "open quote" to "\"", "close quote" to "\"",
    )

    fun clean(rawInput: String, options: Options = Options()): String {
        if (rawInput.isBlank()) return ""
        var text = rawInput.replace(Regex("[ \\t]+"), " ").trim()

        // 1. Perintah tanda baca (sebelum filler dibuang, agar "titik" tidak salah dianggap kata biasa)
        for ((phrase, mark) in punctuationCommands) {
            text = replacePhrase(text, phrase, mark)
        }

        // 2. Kamus pengguna
        for ((from, to) in options.replacements) {
            if (from.isNotBlank()) text = replacePhrase(text, from, to)
        }

        // 3. Filler
        text = text.split(' ').joinToString(" ") { token ->
            val bare = token.trim(' ', ',', '.', '!', '?', ';', ':')
            if (bare.isNotEmpty() && fillers.contains(bare.lowercase())) "" else token
        }
        text = text.replace(Regex("\\s+"), " ")

        // 4. Rapikan tanda baca (tanpa merusak jam "7:30" atau desimal "3.5")
        text = text.replace(Regex("\\s+([,.!?;:])"), "$1")
        text = text.replace(Regex("([,.!?;:])(?=\\p{L})"), "$1 ")
        text = text.replace(Regex("(\\.)\\1{2,}"), "...")
        text = text.replace(Regex("\\(\\s+"), "(").replace(Regex("\\s+\\)"), ")")
        text = text.trim()

        if (options.autoCapitalize) text = capitalizeSentences(text)
        if (options.autoPunctuation && text.isNotEmpty() && !text.trim().endsWithAny()) {
            // Kalau berakhir dengan koma, ganti jadi titik; kalau tidak ada, tambahkan titik.
            text = text.trimEnd().let { if (it.endsWith(",") || it.endsWith(";") || it.endsWith(":")) it.dropLast(1) + "." else "$it." }
        }
        return text.trim()
    }

    private fun String.endsWithAny(): Boolean =
        listOf(".", "?", "!", "...", "\"", ")", "\n").any { trimmedEndsWith(it) }

    private fun String.trimmedEndsWith(suffix: String): Boolean = trimEnd().endsWith(suffix)

    fun capitalizeSentences(text: String): String {
        val sb = StringBuilder(text.length)
        var capitalizeNext = true
        for (ch in text) {
            when {
                capitalizeNext && ch.isLetter() -> {
                    sb.append(ch.uppercaseChar()); capitalizeNext = false
                }
                ch == '\n' -> {
                    sb.append(ch); capitalizeNext = true
                }
                else -> {
                    sb.append(ch)
                    if (ch == '.' || ch == '!' || ch == '?') capitalizeNext = true
                }
            }
        }
        return sb.toString()
    }

    /**
     * Ganti frasa dengan pencocokan utuh per kata (word-boundary) supaya
     * "titik" tidak merusak kata seperti "titiknya" kalau tidak dimaksudkan.
     */
    private fun replacePhrase(text: String, phrase: String, replacement: String): String {
        val quoted = Regex.escape(phrase.trim())
        val pattern = Regex("(?i)(?<![\\p{L}\\p{N}])$quoted(?![\\p{L}\\p{N}])")
        val result = pattern.replace(text) { replacement }
        return if (replacement.length == 1 && replacement[0] in ",.!?;:") {
            // Buang spasi di depan tanda baca agar menempel ke kata sebelumnya.
            result.replace(Regex("\\s+([,.!?;:])"), "$1")
        } else {
            result
        }
    }
}
