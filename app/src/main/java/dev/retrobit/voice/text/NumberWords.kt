package dev.retrobit.voice.text

/**
 * Mengubah angka yang diucapkan menjadi angka numerik.
 * Mendukung Bahasa Indonesia dan Bahasa Inggris (cukup untuk "jam tujuh",
 * "timer lima menit", "volume lima puluh").
 *
 * Ini murni Kotlin (tanpa dependensi Android) sehingga bisa diuji di JVM.
 */
object NumberWords {

    private val idBasic = mapOf(
        "nol" to 0, "kosong" to 0, "sip" to 0,
        "satu" to 1, "se" to 1, "dua" to 2, "tiga" to 3, "empat" to 4, "lima" to 5,
        "enam" to 6, "tujuh" to 7, "delapan" to 8, "sembilan" to 9, "sepuluh" to 10,
        "sebelas" to 11, "duabelas" to 12,
    )

    private val enBasic = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20,
    )

    private val enTens = mapOf(
        "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60,
        "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    /**
     * Membaca angka mulai dari [start]. Mengembalikan pasangan (nilai, jumlah token
     * yang dikonsumsi) atau null kalau token pertama bukan angka.
     */
    fun parse(tokens: List<String>, start: Int = 0): Pair<Int, Int>? {
        if (start < 0 || start >= tokens.size) return null
        val first = tokens[start]

        first.toIntOrNull()?.let { return it to 1 }

        // Bahasa Indonesia
        idBasic[first]?.let { base ->
            // "dua belas", "dua puluh", "dua puluh lima", "dua ratus"
            val next = tokens.getOrNull(start + 1)
            if (base in 1..9 && next != null) {
                when (next) {
                    "belas" -> return (base * 10 + 10) to 2
                    "puluh" -> {
                        val third = tokens.getOrNull(start + 2)
                        val unit = third?.let { idBasic[it] }?.takeIf { it in 1..9 }
                        return if (unit != null) (base * 10 + unit) to 3 else (base * 10) to 2
                    }
                    "ratus" -> {
                        val third = tokens.getOrNull(start + 2)
                        val sub = third?.let { idBasic[it] }?.takeIf { it in 1..9 }
                        return if (sub != null) (sub * 100 + base * 100) to 3 else (base * 100) to 2
                    }
                }
            }
            return base to 1
        }
        if (first == "seratus") {
            val next = tokens.getOrNull(start + 1)
            val sub = next?.let { idBasic[it] }?.takeIf { it in 1..9 }
            return if (sub != null) (100 + sub * 10) to 2 else 100 to 1
        }

        // Bahasa Inggris
        enBasic[first]?.let { base ->
            if (base == 20 && tokens.getOrNull(start + 1) != null) {
                val unit = enBasic[tokens[start + 1]]?.takeIf { it in 1..9 }
                if (unit != null) return (20 + unit) to 2
            }
            return base to 1
        }
        enTens[first]?.let { base ->
            val unit = tokens.getOrNull(start + 1)?.let { enBasic[it] }?.takeIf { it in 1..9 }
            return if (unit != null) (base + unit) to 2 else base to 1
        }
        return null
    }

    /** Cari angka pertama di dalam teks (numerik atau kata). */
    fun findIn(text: String): Int? {
        val tokens = Tokenizer.normalize(text)
        var i = 0
        while (i < tokens.size) {
            val parsed = parse(tokens, i)
            if (parsed != null) return parsed.first
            i++
        }
        return null
    }
}

/** Tokenizer bersama untuk parser perintah dan pembersih teks. */
object Tokenizer {

    /** Normalisasi ringan: huruf kecil, tanda baca dipisah, spasi dirapikan. */
    fun normalize(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[^a-z0-9à-ÿ:.%\\s-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .split(' ')
            .filter { it.isNotBlank() }
}
