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

    // "puka" adalah salah dengar STT yang umum untuk "buka". Alias ini hanya
    // aktif di parser perintah asisten; teks dikte biasa tidak pernah diubah.
    private val openVerbs = listOf("bukakan", "buka", "puka", "jalankan", "open", "launch", "start", "run")
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
    private val brightnessUp = listOf("naikkan kecerahan", "cerahkan layar", "terangkan layar", "brightness up", "brighten screen")
    private val brightnessDown = listOf("turunkan kecerahan", "redupkan layar", "gelapkan layar", "brightness down", "dim screen")
    private val dndOff = listOf("matikan dnd", "nonaktifkan dnd", "matikan mode dnd", "nonaktifkan mode dnd", "dnd off", "turn off dnd", "disable dnd", "matikan jangan ganggu", "nonaktifkan jangan ganggu", "matikan mode jangan ganggu", "nonaktifkan mode jangan ganggu", "jangan ganggu matikan", "do not disturb off", "turn off do not disturb", "disable do not disturb")
    private val dndOn = listOf("aktifkan dnd", "nyalakan dnd", "aktifkan mode dnd", "nyalakan mode dnd", "dnd on", "turn on dnd", "enable dnd", "aktifkan jangan ganggu", "nyalakan jangan ganggu", "aktifkan mode jangan ganggu", "nyalakan mode jangan ganggu", "mode jangan ganggu aktif", "do not disturb on", "turn on do not disturb", "enable do not disturb")
    private val bluetoothOff = listOf("matikan bluetooth", "nonaktifkan bluetooth", "bluetooth off", "turn off bluetooth")
    private val bluetoothOn = listOf("aktifkan bluetooth", "nyalakan bluetooth", "hidupkan bluetooth", "bluetooth on", "turn on bluetooth")
    private val answerCallPhrases = listOf("jawab telepon", "jawab telfon", "angkat telepon", "terima telepon", "jawab panggilan", "angkat panggilan", "terima panggilan", "answer call", "answer the call", "answer incoming call", "accept call", "pick up the phone")
    private val startDictationPhrases = listOf("mulai dikte", "mulai mendikte", "mulai mengetik", "start dictation", "begin dictation", "start typing")
    private val helpPhrases = listOf(
        "apa yang bisa kamu lakukan", "apa saja yang bisa kamu lakukan", "fitur apa saja", "perintah apa saja",
        "what can you do", "what commands can i say", "show commands", "bantuan", "help",
    )
    private val currentTimePhrases = listOf(
        "jam berapa", "pukul berapa", "jam sekarang", "waktu sekarang", "what time is it", "what's the time",
        "what is the time", "current time", "time now",
    )
    private val currentDatePhrases = listOf(
        "tanggal berapa", "tanggal hari ini", "hari ini tanggal", "hari apa sekarang", "what is the date",
        "what's the date", "what date is it", "what day is it", "current date", "today's date",
    )
    private val batteryQueryPhrases = listOf(
        "persentase baterai", "persen baterai", "baterai berapa", "berapa baterai", "status baterai",
        "baterai saya", "daya baterai", "cek baterai", "cek daya", "battery percentage", "battery percent",
        "battery level", "battery status", "battery remaining", "battery left", "how much battery",
        "how much battery is left", "is my phone charging",
    )
    private val weatherPhrases = listOf("cuaca", "suhu", "temperatur", "weather", "temperature")
    private val typeTextVerbs = listOf("masukkan teks", "ketikkan", "ketik", "tuliskan", "tulis", "type", "write")

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
        parseTypeText(rawText)?.let { return it }
        val text = stripLeadIns(rawText).trim()
        if (text.isBlank()) return null
        val tokens = Tokenizer.normalize(text)

        helpPhrases.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.ShowHelp, 0.99f, "help")
        }
        startDictationPhrases.firstOrNull {
            text.equals(it, ignoreCase = true) || text.startsWith("$it ", ignoreCase = true)
        }?.let {
            return Result(AssistantAction.StartDictation, 0.99f, "start-dictation")
        }
        currentDatePhrases.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.GetCurrentDate, 0.99f, "current-date")
        }
        currentTimePhrases.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.GetCurrentTime, 0.99f, "current-time")
        }
        val batteryNounPresent = tokens.any { it in setOf("baterai", "battery") }
        val batteryStatusAsked = batteryQueryPhrases.any { text.contains(it, ignoreCase = true) } ||
            (batteryNounPresent && tokens.any {
                it in setOf("berapa", "persen", "percent", "level", "status", "tersisa", "tinggal", "charging", "charged")
            })
        if (batteryStatusAsked) {
            return Result(AssistantAction.GetBatteryStatus, 0.99f, "battery-status")
        }
        if (weatherPhrases.any { text.contains(it, ignoreCase = true) }) {
            return Result(AssistantAction.GetWeather(extractWeatherLocation(text)), 0.99f, "current-weather")
        }

        // Kontrol sistem harus dikenali lokal agar tidak menunggu LLM.
        dndOff.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetDnd(false), 0.98f, "dnd-off")
        }
        dndOn.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetDnd(true), 0.98f, "dnd-on")
        }
        bluetoothOff.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetBluetooth(false), 0.98f, "bluetooth-off")
        }
        bluetoothOn.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetBluetooth(true), 0.98f, "bluetooth-on")
        }
        answerCallPhrases.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.AnswerCall, 0.98f, "answer-call")
        }

        // Naik/turunkan kecerahan dalam langkah 10%; nilai absolut memakai persen.
        brightnessUp.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetBrightness(10, relative = true), 0.95f, "brightness-up")
        }
        brightnessDown.firstOrNull { text.contains(it, ignoreCase = true) }?.let {
            return Result(AssistantAction.SetBrightness(-10, relative = true), 0.95f, "brightness-down")
        }
        if (tokens.any { it in setOf("kecerahan", "brightness", "terang") }) {
            val percent = extractPercent(tokens, "kecerahan", "brightness", "terang")
            if (percent != null) {
                return Result(AssistantAction.SetBrightness(percent, relative = false), 0.95f, "brightness-set")
            }
        }

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
        openVerbs.firstOrNull {
            text.equals(it, ignoreCase = true) || text.startsWith("$it ", ignoreCase = true)
        }?.let { verb ->
            val target = text.substring(verb.length).trim().trimEnd('.', '!', '?')
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

    private fun parseTypeText(rawText: String): Result? {
        val command = stripLeadInsPreservingText(rawText)
        val verb = typeTextVerbs.firstOrNull { candidate ->
            command.startsWith(candidate, ignoreCase = true) &&
                (command.length == candidate.length || command[candidate.length].isWhitespace() || command[candidate.length] == ':')
        } ?: return null
        val body = command.substring(verb.length).trim().trimStart(':').trimStart()
        if (body.isBlank()) return null
        return Result(AssistantAction.TypeText(body), 0.99f, "type-text")
    }

    private fun stripLeadInsPreservingText(text: String): String {
        var result = text.trim()
        var changed = true
        while (changed) {
            changed = false
            val lead = leadIns.firstOrNull { candidate ->
                result.startsWith(candidate, ignoreCase = true) &&
                    (result.length == candidate.length || result[candidate.length].isWhitespace() || result[candidate.length] in ",:")
            }
            if (lead != null) {
                result = result.substring(lead.length).trimStart(' ', ',', ':')
                changed = true
            }
        }
        return result
    }

    private fun extractWeatherLocation(text: String): String? {
        val locationMarker = Regex("(?:^|\\s)(?:di|in|for|at)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)
        val weatherTerm = weatherPhrases.sortedByDescending(String::length)
            .firstOrNull { text.contains(it, ignoreCase = true) }
        var candidate = locationMarker ?: if (weatherTerm != null) {
            val start = text.indexOf(weatherTerm, ignoreCase = true)
            text.substring(start + weatherTerm.length)
        } else {
            ""
        }

        val leadingNoise = listOf("sekarang", "saat ini", "hari ini", "today", "now", "right now", "currently")
        val trailingNoise = listOf(
            "sekarang", "saat ini", "hari ini", "today", "now", "right now", "currently", "berapa", "derajat", "degrees",
        )
        var changed = true
        while (changed) {
            changed = false
            val prefix = leadingNoise.firstOrNull { candidate.startsWith("$it ", ignoreCase = true) }
            if (prefix != null) {
                candidate = candidate.substring(prefix.length).trimStart()
                changed = true
            }
            val connector = listOf("di", "in", "for", "at").firstOrNull { candidate.startsWith("$it ", ignoreCase = true) }
            if (connector != null) {
                candidate = candidate.substring(connector.length).trimStart()
                changed = true
            }
            val suffix = trailingNoise.firstOrNull { candidate.endsWith(" $it", ignoreCase = true) }
            if (suffix != null) {
                candidate = candidate.dropLast(suffix.length).trimEnd()
                changed = true
            }
        }

        val nonLocationPhrases = setOf(
            "sekarang", "saat ini", "hari ini", "today", "now", "right now", "currently", "berapa", "bagaimana", "apa",
            "what", "what is", "what's", "what is the", "what's the", "how", "how is", "how's", "current", "current weather", "the",
        )
        if (candidate.trim().lowercase() in nonLocationPhrases) candidate = ""
        if (locationMarker == null && candidate.isBlank() && weatherTerm != null) {
            val start = text.indexOf(weatherTerm, ignoreCase = true)
            candidate = text.substring(0, start).trim()
                .replace(Regex("(?i)^(?:berapa|apa|bagaimana|what(?:\\s+is|['’]s)?|how(?:\\s+is|['’]s)?|is|the|current|today)\\s+"), "")
                .replace(Regex("(?i)\\s+(?:berapa|how|today)$"), "")
                .trim()
            if (candidate.lowercase() in nonLocationPhrases) candidate = ""
        }
        val normalized = candidate.trim().trim(',', '.', '!', '?')
        return normalized.takeIf {
            it.isNotBlank() && it.lowercase() !in setOf(
                "sini", "sana", "mana", "here", "there", "near me", "around me", "my area", "lokasi saya", "my location",
            )
        }
    }

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
        .replace(Regex("(?i)^(?:aplikasi|app|the)\\s+"), "")
        .trim()

    private fun extractLabel(text: String, verb: String): String =
        text.substringAfter(verb, "").trim().trimEnd('.', '!', '?').take(60)

    private fun extractPercent(tokens: List<String>, vararg keywords: String): Int? {
        val idx = tokens.indexOfFirst { keywords.contains(it) }
        if (idx < 0) return null
        // Lewati kata penghubung seperti "ke" pada "atur kecerahan ke 50 persen".
        val afterKeyword = (idx + 1 until minOf(tokens.size, idx + 5))
        for (start in afterKeyword) {
            NumberWords.parse(tokens, start)?.let { return it.first.coerceIn(0, 100) }
        }
        for (start in (idx - 1 downTo maxOf(0, idx - 3))) {
            NumberWords.parse(tokens, start)?.let { return it.first.coerceIn(0, 100) }
        }
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
