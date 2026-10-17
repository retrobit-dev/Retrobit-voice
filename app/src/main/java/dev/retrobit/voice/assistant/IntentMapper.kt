package dev.retrobit.voice.assistant

import dev.retrobit.voice.ai.Http
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Mengubah balasan JSON dari LLM menjadi [AssistantAction] yang bisa dijalankan.
 * Kalau JSON tidak valid, seluruh teks dianggap jawaban biasa ("answer").
 */
object IntentMapper {

    data class Mapped(val action: AssistantAction?, val reply: String)

    fun map(raw: String): Mapped {
        val cleaned = stripCodeFence(raw).trim()
        val parsed = runCatching { Http.json.parseToJsonElement(cleaned).jsonObject }.getOrNull()
            ?: return Mapped(null, cleaned.ifBlank { "Maaf, saya belum bisa menjawab itu." })

        val intent = parsed["intent"]?.jsonPrimitive?.content?.lowercase()?.trim()
        val slots = parsed["slots"] as? JsonObject ?: JsonObject(emptyMap())
        val reply = parsed["reply"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: ActionText.describeFromSlots(intent, slots)

        val action = when (intent) {
            "open_app" -> slots.str("app")?.let { AssistantAction.OpenApp(it) }
            "web_search" -> slots.str("query")?.let { AssistantAction.WebSearch(it, slots.str("site")) }
            "call" -> slots.str("contact")?.let { AssistantAction.CallContact(it) }
            "dial" -> slots.str("number")?.let { AssistantAction.Dial(it) }
            "set_alarm" -> parseTime(slots.str("time"))?.let { (h, m) ->
                AssistantAction.SetAlarm(h, m, slots.str("label").orEmpty())
            }
            "set_timer" -> slots.int("seconds")?.takeIf { it > 0 }?.let {
                AssistantAction.SetTimer(it, slots.str("label").orEmpty())
            }
            "open_settings" -> slots.str("panel")?.let { AssistantAction.OpenSettingsPanel(it.lowercase()) }
            "send_message" -> {
                val contact = slots.str("contact")
                val body = slots.str("body").orEmpty()
                if (contact != null) AssistantAction.SendMessage(contact, body) else null
            }
            "save_note" -> slots.str("body")?.let { AssistantAction.SaveNote(it) }
            "torch" -> slots.bool("on")?.let { AssistantAction.SetTorch(it) }
            "volume" -> slots.int("percent")?.let {
                AssistantAction.SetVolume(it, relative = slots.bool("relative") == true)
            }
            "brightness", "set_brightness" -> slots.int("percent")?.let {
                AssistantAction.SetBrightness(it, relative = slots.bool("relative") == true)
            }
            "dnd", "do_not_disturb" -> slots.bool("on")?.let { AssistantAction.SetDnd(it) }
            "bluetooth" -> slots.bool("on")?.let { AssistantAction.SetBluetooth(it) }
            "answer_call" -> AssistantAction.AnswerCall
            "current_time", "get_current_time" -> AssistantAction.GetCurrentTime
            "current_date", "get_current_date" -> AssistantAction.GetCurrentDate
            "battery_status", "get_battery_status" -> AssistantAction.GetBatteryStatus
            "weather", "current_weather" -> AssistantAction.GetWeather(slots.str("location"))
            "type_text" -> slots.str("text")?.let { AssistantAction.TypeText(it) }
            "start_dictation" -> AssistantAction.StartDictation
            "open_whatsapp_chat", "whatsapp_chat" -> slots.str("contact")?.let {
                AssistantAction.OpenWhatsAppChat(it, preparingCall = slots.bool("for_call") == true)
            }
            "media_control" -> slots.str("command")?.uppercase()?.let { command ->
                runCatching { MediaCommand.valueOf(command) }.getOrNull()?.let { AssistantAction.ControlMedia(it) }
            }
            "help" -> AssistantAction.ShowHelp
            else -> null
        }

        // Untuk intent "answer" (atau slot tidak lengkap) → tidak ada aksi, hanya jawaban.
        return Mapped(action, reply)
    }

    private fun stripCodeFence(text: String): String {
        var t = text.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```json").removePrefix("```").trim()
            if (t.endsWith("```")) t = t.dropLast(3).trim()
        }
        val first = t.indexOf('{')
        val last = t.lastIndexOf('}')
        return if (first >= 0 && last > first) t.substring(first, last + 1) else t
    }

    private fun parseTime(value: String?): Pair<Int, Int>? {
        if (value.isNullOrBlank()) return null
        val parts = value.trim().split(':')
        val h = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()?.toInt()

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
            when (it) {
                "true", "1", "yes", "ya" -> true
                "false", "0", "no", "tidak" -> false
                else -> null
            }
        }
}

/** Kalimat siap-ucap untuk setiap aksi — dipakai sebagai konfirmasi & riwayat. */
object ActionText {

    fun describe(action: AssistantAction): String = when (action) {
        is AssistantAction.OpenApp -> "Membuka ${action.appQuery}."
        is AssistantAction.WebSearch -> "Mencari \"${action.query}\"" + (action.site?.let { " di $it" } ?: " di Google") + "."
        is AssistantAction.Dial -> "Membuka panggilan ke ${action.target}."
        is AssistantAction.CallContact -> "Menelepon ${action.name}."
        is AssistantAction.SetAlarm -> "Alarm ${fmt(action.hour, action.minute)} diset" + action.label.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + "."
        is AssistantAction.SetTimer -> "Timer ${humanDuration(action.seconds)} diset."
        is AssistantAction.OpenSettingsPanel -> "Membuka pengaturan ${action.panel}."
        is AssistantAction.SendMessage -> "Menyiapkan pesan untuk ${action.contactName}."
        is AssistantAction.SaveNote -> "Catatan disimpan."
        is AssistantAction.SetTorch -> if (action.on) "Senter dinyalakan." else "Senter dimatikan."
        is AssistantAction.SetVolume ->
            if (action.relative) {
                if (action.percent >= 0) "Volume dinaikkan." else "Volume diturunkan."
            } else {
                "Volume diatur ke ${action.percent} persen."
            }
        is AssistantAction.SetBrightness ->
            if (action.relative) {
                if (action.percent >= 0) "Kecerahan layar dinaikkan." else "Kecerahan layar diturunkan."
            } else {
                "Kecerahan layar diatur ke ${action.percent} persen."
            }
        is AssistantAction.SetDnd -> if (action.enabled) "Mode Jangan Ganggu dinyalakan." else "Mode Jangan Ganggu dimatikan."
        is AssistantAction.SetBluetooth ->
            "Pengaturan Bluetooth dibuka untuk ${if (action.enabled) "menyalakan" else "mematikan"} Bluetooth; konfirmasikan perubahan di sana."
        AssistantAction.AnswerCall -> "Mencoba menjawab panggilan masuk."
        AssistantAction.GetCurrentTime -> "Memeriksa waktu lokal perangkat."
        AssistantAction.GetCurrentDate -> "Memeriksa tanggal lokal perangkat."
        AssistantAction.GetBatteryStatus -> "Memeriksa status baterai perangkat."
        is AssistantAction.GetWeather -> action.location?.let { "Memeriksa cuaca terkini di $it." }
            ?: "Sebutkan nama kota agar saya bisa memeriksa cuacanya."
        is AssistantAction.TypeText -> "Mengetik teks pada posisi kursor yang aktif."
        AssistantAction.StartDictation -> "Memulai dikte."
        is AssistantAction.OpenWhatsAppChat -> if (action.preparingCall) {
            "Membuka chat WhatsApp dengan ${action.contactName}; panggilan perlu dimulai dari WhatsApp."
        } else {
            "Membuka chat WhatsApp dengan ${action.contactName}."
        }
        is AssistantAction.ControlMedia -> when (action.command) {
            MediaCommand.PLAY -> "Mengirim perintah putar ke media aktif."
            MediaCommand.PAUSE -> "Mengirim perintah jeda ke media aktif."
            MediaCommand.STOP -> "Mengirim perintah berhenti ke media aktif."
            MediaCommand.NEXT -> "Melewati media aktif ke berikutnya."
            MediaCommand.PREVIOUS -> "Kembali ke media sebelumnya."
        }
        AssistantAction.ShowHelp -> "Menampilkan perintah yang bisa digunakan."
        is AssistantAction.Unknown -> "Maaf, saya belum paham perintah itu."
    }

    /** Dipakai ketika LLM tidak mengisi "reply" tetapi slot-nya lengkap. */
    fun describeFromSlots(intent: String?, slots: JsonObject): String = when (intent) {
        "open_app" -> "Membuka aplikasi."
        "web_search" -> "Mencari di web."
        "call" -> "Menelepon kontak."
        "set_alarm" -> "Alarm diset."
        "set_timer" -> "Timer diset."
        "send_message" -> "Menyiapkan pesan."
        "save_note" -> "Catatan disimpan."
        "torch" -> "Mengubah senter."
        "volume" -> "Mengubah volume."
        "brightness", "set_brightness" -> "Mengubah kecerahan layar."
        "dnd", "do_not_disturb" -> "Mengubah mode Jangan Ganggu."
        "bluetooth" -> "Membuka pengaturan Bluetooth untuk konfirmasi pengguna."
        "answer_call" -> "Mencoba menjawab panggilan masuk."
        "current_time", "get_current_time" -> "Memeriksa waktu lokal perangkat."
        "current_date", "get_current_date" -> "Memeriksa tanggal lokal perangkat."
        "battery_status", "get_battery_status" -> "Memeriksa status baterai perangkat."
        "weather", "current_weather" -> "Memeriksa cuaca terkini."
        "type_text" -> "Mengetik teks pada posisi kursor yang aktif."
        "start_dictation" -> "Memulai dikte."
        "open_whatsapp_chat", "whatsapp_chat" -> "Membuka chat WhatsApp."
        "media_control" -> "Mengontrol pemutar media yang aktif."
        "help" -> "Menampilkan perintah yang bisa digunakan."
        "open_settings" -> "Membuka pengaturan."
        else -> "Baik."
    }

    private fun fmt(h: Int, m: Int): String = "%02d:%02d".format(h, m)

    fun humanDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return buildList {
            if (h > 0) add("$h jam")
            if (m > 0) add("$m menit")
            if (s > 0) add("$s detik")
        }.joinToString(" ").ifBlank { "$seconds detik" }
    }
}
