package dev.retrobit.voice.assistant

import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.text.NumberWords
import dev.retrobit.voice.text.Tokenizer

/**
 * Parser perintah offline (tanpa jaringan, tanpa LLM).
 *
 * Menangani perintah umum Bahasa Indonesia + Inggris:
 *   "buka whatsapp", "cari resep rendang", "telepon ibu",
 *   "alarm jam 5 pagi", "timer 10 menit", "catat beli galon",
 *   "nyalakan senter", "volume 70 persen", "kirim pesan ke budi aku otw"
 *
 * Kalau tidak ada pola yang cocok, kembalikan null sehingga pemanggil bisa
 * meneruskan ke LLM (kalau dikonfigurasi) atau menawarkan pencarian web.
 *
 * Murni Kotlin → bisa diuji di JVM (lihat CommandParserTest).
 */
object CommandParser {

    /** Frasa pembuka yang boleh diabaikan di depan perintah. */
    private val leadIns = listOf(
        "tolong", "coba", "silakan", "please", "hey retro", "hai retro", "hey", "hi",
        "retro", "bisa", "bisakah", "can you", "could you", "kumohon",
    )

    private val openVerbs = listOf("buka", "bukakan", "jalankan", "open", "launch", "start", "run")
    private val searchVerbs = listOf("cari", "carikan", "telusuri", "search", "google", "look up")
    private val callVerbs = listOf("telepon", "telpon", "panggil", "call", "phone", "dial", "hubungi")
    private val alarmVerbs = listOf("alarm", "set alarm", "bangunkan", "pasang alarm", "buat alarm", "setel alarm")
    private val timerVerbs = listOf("timer", "set timer", "hitung mundur", "countdown", "pasang timer")
    private val noteVerbs = listOf("catat", "catatan", "note", "remember", "ingatkan", "simpan catatan")
    private val messageVerbs = listOf("kirim pesan", "kirim wa", "kirim chat", "pesan ke", "send message", "message", "wa ke", "chat ke")
    private val torchOn = listOf("nyalakan senter", "hidupkan senter", "nyalakan flash", "turn on flashlight", "torch on", "nyalain senter")
    private val torchOff = listOf("matikan senter", "matikan flash", "turn off flashlight", "torch off", "matiin senter")
    private val volumeUp = listOf("keraskan volume", "naikkan volume", "volume naik", "turn up volume", "volume up")
    private val volumeDown = listOf("kecilkan volume", "turunkan volume", "volume turun", "turn down volume", "volume down", "turunkan suara")

    private val settingsMap = linkedMapOf(
        "wifi" to "wifi", "wi-fi" to "wifi", "bluetooth" to "bluetooth", "lokasi" to "location",
        "gps" to "location", "data seluler" to "data", "seluler" to "data", "baterai" to "battery",
        "tampilan" to "display", "layar" to "display", "suara" to "sound", "nada" to "sound",
        "aplikasi" to "apps", "penyimpanan" to "storage", "tanggal" to "date", "jam" to "date",
        "aksesibilitas" to "accessibility", "keyboard" to "keyboard",
    )

    private val webSites = mapOf(
        "youtube" to "https://www.youtube.com/results?search_query=",
        "maps" to "https://www.google.com/maps/search/",
        "google maps" to "https://www.google.com/maps/search/",
        "play store" to "https://play.google.com/store/search?q=",
        "tokopedia" to "https://www.tokopedia.com/search?st=product&q=",
        "shopee" to "https://shopee.co.id/search?keyword=",
        "wikipedia" to "https://id.wikipedia.org/w/index.php?search=",
        "instagram" to "https://www.instagram.com/explore/tags/",
    )

    /** Hasil parsing: aksi + sisa teks yang mungkin perlu dipahami LLM. */
    data class Result(val action: AssistantAction, val confidence: Float, val matchedRule: String)

    fun parse(rawText: String): Result? {
        val text = stripLeadIns(rawText).trim()
        if (text.isBlank()) return null
        val tokens = Tokenizer.normalize(text)

        // 1. Senter
        torchOn.firstOrNull { text.contains(it) }?.let { return Result(AssistantAction.SetTorch(true), 0.95f, "torch-on:$it") }
        torchOff.firstOrNull { text.contains(it) }?.let { return Result(AssistantAction.SetTorch(false), 0.95f, "torch-off:$it") }

        // 2. Volume
        volumeUp.firstOrNull { text.contains(it) }?.let { return Result(AssistantAction.SetVolume(1, relative = true), 0.9f, "volume-up") }
        volumeDown.firstOrNull { text.contains(it) }?.let { return Result(AssistantAction.SetVolume(-1, relative = true), 0.9f, "volume-down") }
        if (tokens.any { it == "volume" || it == "suara" }) {
            val percent = extractPercent(tokens, "volume", "suara")
            if (percent != null) return Result(AssistantAction.SetVolume(percent, relative = false), 0.9f, "volume-set")
        }

        // 3. Alarm & timer
        alarmVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            parseClock(tokens)?.let { (h, m) ->
                return Result(AssistantAction.SetAlarm(h, m, label = extractLabel(text, verb)), 0.9f, "alarm")
            }
        }
        timerVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            parseDurationSeconds(tokens)?.let { secs ->
                return Result(AssistantAction.SetTimer(secs, label = extractLabel(text, verb)), 0.9f, "timer")
            }
        }

        // 4. Kirim pesan
        messageVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            parseMessage(text, verb)?.let { (name, body) ->
                return Result(AssistantAction.SendMessage(name, body), 0.75f, "message")
            }
        }

        // 5. Telepon
        callVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            val target = text.substringAfter(verb, "").trim().trimEnd('.', '!', '?')
            if (target.isNotBlank()) {
                val digits = target.filter { it.isDigit() || it == '+' }
                return if (digits.length >= 5) {
                    Result(AssistantAction.Dial(digits), 0.9f, "dial-number")
                } else {
                    Result(AssistantAction.CallContact(target), 0.8f, "call-contact")
                }
            }
        }

        // 6. Buka aplikasi / panel pengaturan
        // STT sering mengkapitalkan kata pertama ("Buka WhatsApp"). Cocokkan
        // verba tanpa membedakan kapitalisasi dan ambil target dari teks asli.
        openVerbs.firstOrNull { text.contains(it, ignoreCase = true) }?.let { verb ->
            val verbStart = text.indexOf(verb, ignoreCase = true)
            val target = text.substring(verbStart + verb.length).trim().trimEnd('.', '!', '?')
            if (target.isNotBlank()) {
                val panel = settingsMap.entries.firstOrNull { target.contains(it.key, ignoreCase = true) }?.value
                return if (panel != null && (target.contains("pengaturan", ignoreCase = true) || target.contains("setting", ignoreCase = true))) {
                    Result(AssistantAction.OpenSettingsPanel(panel), 0.85f, "open-settings")
                } else {
                    Result(AssistantAction.OpenApp(cleanAppName(target)), 0.85f, "open-app")
                }
            }
        }

        // 7. Catatan
        noteVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            val body = text.substringAfter(verb, "").trim()
            if (body.isNotBlank()) return Result(AssistantAction.SaveNote(body), 0.85f, "note")
        }

        // 8. Pencarian web
        searchVerbs.firstOrNull { text.contains(it) }?.let { verb ->
            var query = text.substringAfter(verb, "").trim().trimEnd('.', '!', '?')
            var site: String? = null
            for ((key, url) in webSites) {
                if (query.startsWith("di $key") || query.startsWith("$key")) {
                    site = url
                    query = query.removePrefix("di $key").removePrefix(key).trim()
                    break
                }
            }
            if (query.isNotBlank()) return Result(AssistantAction.WebSearch(query, site), 0.85f, "web-search")
        }

        return null
    }

    /** Pencarian web dipakai sebagai fallback terakhir kalau tidak ada aturan yang cocok. */
    fun asWebSearchFallback(rawText: String): AssistantAction.WebSearch =
        AssistantAction.WebSearch(stripLeadIns(rawText).trim())

    private fun stripLeadIns(text: String): String {
        var result = text.trim().trimEnd('.', '!', '?')
        var changed = true
        while (changed) {
            changed = false
            for (lead in leadIns) {
                if (result.lowercase().startsWith(lead + " ")) {
                    result = result.substring(lead.length).trim()
                    changed = true
                }
            }
        }
        return result
    }

    private fun cleanAppName(target: String): String = target
        .removePrefix("aplikasi ").removePrefix("app ").removePrefix("the ")
        .trim()

    private fun extractLabel(text: String, verb: String): String =
        text.substringAfter(verb, "").trim().trimEnd('.', '!', '?').take(60)

    private fun extractPercent(tokens: List<String>, vararg keywords: String): Int? {
        val idx = tokens.indexOfFirst { keywords.contains(it) }
        if (idx < 0) return null
        // cari angka setelah kata kunci, lalu sebelum
        NumberWords.parse(tokens, idx + 1)?.let { return it.first.coerceIn(0, 100) }
        if (idx > 0) NumberWords.parse(tokens, idx - 1)?.let { return it.first.coerceIn(0, 100) }
        return null
    }

    /** Membaca "jam 7", "jam 7:30", "jam tujuh pagi", "half past seven" (sebagian). */
    fun parseClock(tokens: List<String>): Pair<Int, Int>? {
        var hour: Int? = null
        var minute = 0

        // format "7:30"
        for (t in tokens) {
            if (t.contains(':')) {
                val parts = t.split(':')
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = parts.getOrNull(1)?.toIntOrNull()
                if (h != null && h in 0..23 && m != null && m in 0..59) return h to m
            }
        }

        val jamIdx = tokens.indexOfFirst { it == "jam" || it == "pukul" || it == "at" }
        val startIdx = if (jamIdx >= 0) {
            jamIdx + 1
        } else {
            (0 until tokens.size).firstOrNull { NumberWords.parse(tokens, it)?.first != null } ?: -1
        }
        if (startIdx in tokens.indices) {
            val parsed = NumberWords.parse(tokens, startIdx)
            if (parsed != null) {
                hour = parsed.first
                val next = tokens.getOrNull(startIdx + parsed.second)
                if (next == "lewat" || next == "lebih" || next == "past") {
                    val extra = NumberWords.parse(tokens, startIdx + parsed.second + 1)
                    if (extra != null) minute = extra.first
                }
            }
        }
        if (hour == null) return null
        if (hour !in 0..23) return null

        val tail = tokens.drop(startIdx)
        val isPm = tail.any { it == "siang" || it == "sore" || it == "malam" || it == "pm" }
        val isAm = tail.any { it == "pagi" || it == "subuh" || it == "am" }
        var h = hour
        if (isPm && h in 1..11) h += 12
        if (isAm && h == 12) h = 0
        return h to minute
    }

    /** Membaca "10 menit", "90 detik", "1 jam 15 menit", "5 menit lagi". */
    fun parseDurationSeconds(tokens: List<String>): Int? {
        var total = 0
        var i = 0
        var found = false
        while (i < tokens.size) {
            val parsed = NumberWords.parse(tokens, i)
            if (parsed != null) {
                val unit = tokens.getOrNull(i + parsed.second)
                val multiplier = when (unit) {
                    "jam", "hour", "hours", "h" -> 3600
                    "menit", "minute", "minutes", "min", "mnt" -> 60
                    "detik", "sekon", "second", "seconds", "sec", "det" -> 1
                    else -> null
                }
                if (multiplier != null) {
                    total += parsed.first * multiplier
                    found = true
                    i += parsed.second + 1
                    continue
                }
            }
            i++
        }
        return if (found && total > 0) total else null
    }

    /** "kirim pesan ke budi isinya aku otw" → ("budi", "aku otw"). */
    fun parseMessage(text: String, verb: String): Pair<String, String>? {
        val after = text.substringAfter(verb, "").trim()
        val bodyMarkers = listOf("isinya", "isi", "bilang", "katakan", "berisi", "saying", "said", ":", "bahwa")
        var namePart = after
        var body = ""
        for (marker in bodyMarkers) {
            val idx = after.indexOf(" $marker ", ignoreCase = true)
            if (idx > 0) {
                namePart = after.substring(0, idx)
                body = after.substring(idx + marker.length + 1)
                break
            }
        }
        val name = namePart.trim().removePrefix("ke ").removePrefix("untuk ").removePrefix("to ").trim()
        if (name.isBlank()) return null
        return name to body.trim()
    }
}
