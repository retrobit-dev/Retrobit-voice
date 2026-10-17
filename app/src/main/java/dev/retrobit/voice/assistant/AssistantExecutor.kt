package dev.retrobit.voice.assistant

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.view.KeyEvent
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.MediaCommand
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.HistoryStore
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import dev.retrobit.voice.dictation.insert.TextInserter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Menjalankan aksi asisten di Android.
 *
 * Prinsip: pakai Intent resmi (ACTION_DIAL, ACTION_SET_ALARM, ACTION_VIEW …)
 * supaya tidak butuh izin berbahaya dan tidak "menyamar" sebagai aplikasi lain.
 * Panggilan telepon dibuka di dialer (pengguna tetap menekan tombol panggil) kecuali
 * izin CALL_PHONE diberikan; pesan disiapkan di aplikasi pesan pengguna.
 */
class AssistantExecutor(
    private val context: Context,
    private val history: HistoryStore,
    private val textInserter: TextInserter,
) {

    data class Outcome(
        val success: Boolean,
        val message: String,
        val needsPermission: String? = null,
    )

    private val apps = AppResolver(context)
    private val contacts = ContactsResolver(context)
    private val weatherClient = CurrentWeatherClient()
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    fun installedAppNames(): List<String> = apps.installedApps()

    suspend fun execute(action: AssistantAction, settings: AppSettings): Outcome = withContext(Dispatchers.Main) {
        try {
            when (action) {
                is AssistantAction.OpenApp -> openApp(action.appQuery)
                is AssistantAction.WebSearch -> webSearch(action.query, action.site)
                is AssistantAction.Dial -> dial(action.target, action.contactName)
                is AssistantAction.CallContact -> callContact(action.name, settings)
                is AssistantAction.SetAlarm -> setAlarm(action)
                is AssistantAction.SetTimer -> setTimer(action)
                is AssistantAction.OpenSettingsPanel -> openSettings(action.panel)
                is AssistantAction.SendMessage -> sendMessage(action)
                is AssistantAction.SaveNote -> saveNote(action.body)
                is AssistantAction.SetTorch -> setTorch(action.on)
                is AssistantAction.SetVolume -> setVolume(action)
                is AssistantAction.SetBrightness -> setBrightness(action)
                is AssistantAction.SetDnd -> setDnd(action.enabled)
                is AssistantAction.SetBluetooth -> setBluetooth(action.enabled)
                AssistantAction.AnswerCall -> answerCall()
                AssistantAction.GetCurrentTime -> currentTime(settings)
                AssistantAction.GetCurrentDate -> currentDate(settings)
                AssistantAction.GetBatteryStatus -> batteryStatus(settings)
                is AssistantAction.GetWeather -> currentWeather(action.location, settings)
                is AssistantAction.TypeText -> typeText(action.text, settings)
                AssistantAction.StartDictation -> startDictation(settings)
                is AssistantAction.OpenWhatsAppChat -> openWhatsAppChat(action, settings)
                is AssistantAction.ControlMedia -> controlMedia(action.command, settings)
                AssistantAction.ShowHelp -> showHelp(settings)
                is AssistantAction.Unknown -> Outcome(false, "Perintah tidak dikenali")
            }
        } catch (t: Throwable) {
            VoiceLog.e("Eksekusi aksi gagal", t)
            Outcome(false, "Gagal menjalankan: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ---------------- Aksi ----------------

    private fun openApp(query: String): Outcome {
        val target = apps.resolve(query)
        if (target != null) {
            try {
                context.startActivity(target.intent)
                return Outcome(true, "Membuka ${target.label}.")
            } catch (t: ActivityNotFoundException) {
                VoiceLog.w("Launcher ${target.label} tidak terbuka; mencoba intent paket yang dikenal")
            }
        }

        // Cadangan eksplisit untuk WhatsApp bila label launcher tidak terbaca
        // oleh PackageManager (mis. pembatasan visibilitas paket Android).
        val knownPackage = knownMessagingApp(query)
        if (knownPackage != null) {
            val (packageName, label) = knownPackage
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                return try {
                    context.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    Outcome(true, "Membuka $label.")
                } catch (t: Throwable) {
                    Outcome(false, "Tidak bisa membuka $label.")
                }
            }
        }

        // Perintah "buka aplikasi" tidak boleh diam-diam berubah menjadi pencarian web.
        return Outcome(false, "Aplikasi \"$query\" tidak ditemukan atau tidak dapat dibuka.")
    }

    private fun knownMessagingApp(query: String): Pair<String, String>? {
        val normalized = query.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return when (normalized) {
            "whatsapp", "wa" -> "com.whatsapp" to "WhatsApp"
            "whatsapp business", "wa business" -> "com.whatsapp.w4b" to "WhatsApp Business"
            else -> null
        }
    }

    private fun webSearch(query: String, site: String? = null): Outcome {
        if (query.isBlank()) return Outcome(false, "Kata kunci pencarian kosong")
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = when (site?.lowercase()) {
            "youtube" -> "https://www.youtube.com/results?search_query=$encoded"
            "maps", "google maps" -> "https://www.google.com/maps/search/$encoded"
            "play store" -> "https://play.google.com/store/search?q=$encoded"
            "tokopedia" -> "https://www.tokopedia.com/search?st=product&q=$encoded"
            "shopee" -> "https://shopee.co.id/search?keyword=$encoded"
            "wikipedia" -> "https://id.wikipedia.org/w/index.php?search=$encoded"
            "instagram" -> "https://www.instagram.com/explore/tags/$encoded"
            else -> "https://www.google.com/search?q=$encoded"
        }
        return launchUrl(url, "Mencari \"$query\".")
    }

    private fun launchUrl(url: String, successMessage: String): Outcome = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Outcome(true, successMessage)
    } catch (t: Throwable) {
        // Cadangan: lempar ke aplikasi pencarian sistem
        try {
            context.startActivity(
                Intent(Intent.ACTION_WEB_SEARCH)
                    .putExtra(SearchManager.QUERY, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Outcome(true, successMessage)
        } catch (t2: Throwable) {
            Outcome(false, "Tidak ada aplikasi yang bisa membuka tautan")
        }
    }

    private fun dial(number: String, name: String?): Outcome {
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (clean.length < 3) return Outcome(false, "Nomor tidak valid")
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        val intent = Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome(true, "Memanggil ${name ?: clean}.")
        } catch (t: Throwable) {
            Outcome(false, "Aplikasi telepon tidak tersedia")
        }
    }

    private fun callContact(name: String, settings: AppSettings): Outcome {
        val english = assistantLocale(settings.assistantLanguage).language == "en"
        return when (val result = contacts.lookup(name)) {
            ContactsResolver.Lookup.PermissionRequired -> Outcome(
                false,
                if (english) "Contacts permission is required to find $name in your phone address book."
                else "Izin Kontak diperlukan untuk mencari $name di buku alamat ponsel.",
                Manifest.permission.READ_CONTACTS,
            )
            ContactsResolver.Lookup.NotFound -> Outcome(
                false,
                if (english) "I couldn't find $name in your phone contacts." else "Kontak $name tidak ditemukan di buku alamat ponsel.",
            )
            is ContactsResolver.Lookup.Ambiguous -> Outcome(
                false,
                if (english) "I found several matching contacts: ${result.names.joinToString(", ")}. Please say the full name."
                else "Ada beberapa kontak yang cocok: ${result.names.joinToString(", ")}. Sebutkan nama lengkap.",
            )
            is ContactsResolver.Lookup.Found -> dial(result.contact.number, result.contact.displayName)
        }
    }

    private fun openWhatsAppChat(action: AssistantAction.OpenWhatsAppChat, settings: AppSettings): Outcome {
        val contactName = action.contactName
        val english = assistantLocale(settings.assistantLanguage).language == "en"
        val contact = when (val lookup = contacts.lookup(contactName)) {
            ContactsResolver.Lookup.PermissionRequired -> return Outcome(
                success = false,
                message = if (english) {
                    "Contacts permission is needed to find $contactName in your phone address book. WhatsApp's private contact list is not directly accessible."
                } else {
                    "Izin Kontak diperlukan untuk mencari $contactName di buku alamat ponsel. Daftar internal WhatsApp tidak bisa dibaca langsung."
                },
                needsPermission = Manifest.permission.READ_CONTACTS,
            )
            ContactsResolver.Lookup.NotFound -> return Outcome(
                false,
                if (english) "I couldn't find $contactName in your phone address book. Try the contact's full name."
                else "Kontak $contactName tidak ditemukan di buku alamat ponsel. Coba sebutkan nama lengkap.",
            )
            is ContactsResolver.Lookup.Ambiguous -> return Outcome(
                false,
                if (english) "Several contacts match: ${lookup.names.joinToString(", ")}. Please say the full name."
                else "Ada beberapa kontak yang cocok: ${lookup.names.joinToString(", ")}. Sebutkan nama lengkap.",
            )
            is ContactsResolver.Lookup.Found -> lookup.contact
        }
        val e164 = runCatching {
            PhoneNumberUtils.formatNumberToE164(contact.number, Locale.getDefault().country)
        }.getOrNull() ?: contact.number
        val number = e164.filter(Char::isDigit)
        if (number.length < 5) {
            return Outcome(false, if (english) "The phone number for ${contact.displayName} is invalid." else "Nomor telepon ${contact.displayName} tidak valid.")
        }

        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (packageName in packages) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number"))
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null) {
                return try {
                    context.startActivity(intent)
                    Outcome(
                        true,
                        when {
                            english && action.preparingCall -> "WhatsApp chat with ${contact.displayName} is open. I can't start a WhatsApp call automatically; tap the phone icon in WhatsApp. No call was placed."
                            !english && action.preparingCall -> "Chat WhatsApp dengan ${contact.displayName} dibuka. Saya tidak bisa memulai panggilan WhatsApp otomatis; tekan ikon telepon di WhatsApp. Belum ada panggilan yang dibuat."
                            english -> "WhatsApp chat with ${contact.displayName} is open. You can type a message; it won't be sent automatically."
                            else -> "Chat WhatsApp dengan ${contact.displayName} dibuka. Anda bisa mengetik pesan; pesan tidak dikirim otomatis."
                        },
                    )
                } catch (error: Throwable) {
                    VoiceLog.e("Tidak dapat membuka chat WhatsApp", error)
                    Outcome(false, if (english) "WhatsApp couldn't open that chat." else "WhatsApp tidak dapat membuka chat tersebut.")
                }
            }
        }
        return Outcome(false, if (english) "WhatsApp is not installed or can't open this chat." else "WhatsApp tidak terpasang atau tidak dapat membuka chat ini.")
    }

    private fun controlMedia(command: MediaCommand, settings: AppSettings): Outcome {
        val manager = audioManager
            ?: return Outcome(false, if (assistantLocale(settings.assistantLanguage).language == "en") "Media controls are unavailable on this device." else "Kontrol media tidak tersedia di perangkat ini.")
        val keyCode = when (command) {
            MediaCommand.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaCommand.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaCommand.STOP -> KeyEvent.KEYCODE_MEDIA_STOP
            MediaCommand.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaCommand.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        return try {
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            val verb = when (command) {
                MediaCommand.PLAY -> if (assistantLocale(settings.assistantLanguage).language == "en") "play" else "putar"
                MediaCommand.PAUSE -> if (assistantLocale(settings.assistantLanguage).language == "en") "pause" else "jeda"
                MediaCommand.STOP -> if (assistantLocale(settings.assistantLanguage).language == "en") "stop" else "hentikan"
                MediaCommand.NEXT -> if (assistantLocale(settings.assistantLanguage).language == "en") "next" else "berikutnya"
                MediaCommand.PREVIOUS -> if (assistantLocale(settings.assistantLanguage).language == "en") "previous" else "sebelumnya"
            }
            Outcome(
                true,
                if (assistantLocale(settings.assistantLanguage).language == "en") "Sent the $verb command to the active media player."
                else "Perintah $verb dikirim ke pemutar media aktif.",
            )
        } catch (error: Throwable) {
            VoiceLog.e("Tidak dapat mengirim perintah ke pemutar media", error)
            Outcome(false, if (assistantLocale(settings.assistantLanguage).language == "en") "Couldn't control the active media player." else "Tidak dapat mengontrol pemutar media aktif.")
        }
    }

    private fun setAlarm(action: AssistantAction.SetAlarm): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, action.hour)
            putExtra(AlarmClock.EXTRA_MINUTES, action.minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (action.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Outcome(true, "Alarm ${"%02d:%02d".format(action.hour, action.minute)} diset.")
        } catch (t: Throwable) {
            Outcome(false, "Aplikasi jam bawaan tidak menerima permintaan alarm")
        }
    }

    private fun setTimer(action: AssistantAction.SetTimer): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, action.seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (action.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, action.label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Outcome(true, "Timer ${ActionText.humanDuration(action.seconds)} diset.")
        } catch (t: Throwable) {
            Outcome(false, "Aplikasi jam bawaan tidak menerima permintaan timer")
        }
    }

    private fun openSettings(panel: String): Outcome {
        val action = when (panel) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location", "gps", "lokasi" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "data", "seluler" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "battery", "baterai" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display", "tampilan" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "suara", "nada" -> Settings.ACTION_SOUND_SETTINGS
            "apps", "aplikasi" -> Settings.ACTION_APPLICATION_SETTINGS
            "storage", "penyimpanan" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date", "jam", "tanggal" -> Settings.ACTION_DATE_SETTINGS
            "accessibility", "aksesibilitas" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "keyboard" -> Settings.ACTION_INPUT_METHOD_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(true, "Membuka pengaturan $panel.")
        } catch (t: Throwable) {
            Outcome(false, "Panel pengaturan tidak tersedia di perangkat ini")
        }
    }

    private fun currentTime(settings: AppSettings): Outcome {
        val locale = assistantLocale(settings.assistantLanguage)
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val timePattern = if (locale.language == "en") "h:mm a" else "HH.mm"
        val time = now.format(DateTimeFormatter.ofPattern(timePattern, locale))
        val zone = now.zone
        val zoneLabel = if (locale.language == "id") {
            when (zone.id) {
                "Asia/Jakarta", "Asia/Pontianak", "Asia/Pangkal_Pinang" -> "WIB"
                "Asia/Makassar", "Asia/Bali", "Asia/Manado", "Asia/Kendari", "Asia/Kupang", "Asia/Ujung_Pandang" -> "WITA"
                "Asia/Jayapura", "Asia/Timor" -> "WIT"
                else -> zone.getDisplayName(TextStyle.SHORT, locale)
            }
        } else {
            zone.getDisplayName(TextStyle.SHORT, locale)
        }
        val message = if (locale.language == "en") "The time is $time $zoneLabel." else "Sekarang pukul $time $zoneLabel."
        return Outcome(true, message)
    }

    private fun currentDate(settings: AppSettings): Outcome {
        val locale = assistantLocale(settings.assistantLanguage)
        val date = LocalDate.now(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
        val message = if (locale.language == "en") "Today is $date." else "Hari ini $date."
        return Outcome(true, message)
    }

    private fun batteryStatus(settings: AppSettings): Outcome {
        val english = assistantLocale(settings.assistantLanguage).language == "en"
        val battery = context.getSystemService(BatteryManager::class.java)
            ?: return Outcome(false, if (english) "Battery status is unavailable on this device." else "Status baterai tidak tersedia di perangkat ini.")
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
            ?: return Outcome(false, if (english) "Battery percentage is unavailable on this device." else "Persentase baterai tidak tersedia di perangkat ini.")
        val message = if (english) {
            "Battery is at $level percent${if (battery.isCharging) " and charging" else ""}."
        } else {
            "Baterai tersisa $level persen${if (battery.isCharging) " dan sedang diisi" else ""}."
        }
        return Outcome(true, message)
    }

    private suspend fun currentWeather(location: String?, settings: AppSettings): Outcome {
        if (location.isNullOrBlank()) {
            val message = if (assistantLocale(settings.assistantLanguage).language == "en") {
                "Please name a city, for example: weather in Jepara."
            } else {
                "Sebutkan nama kota, misalnya: cuaca di Jepara. Saya tidak memakai lokasi GPS."
            }
            return Outcome(false, message)
        }
        return try {
            Outcome(true, weatherClient.currentWeather(location, settings.assistantLanguage))
        } catch (error: IOException) {
            VoiceLog.w("Data cuaca tidak dapat diambil: ${error.message}")
            val cityNotFound = error.message.orEmpty().contains("tidak ditemukan", ignoreCase = true)
            val message = when {
                cityNotFound && assistantLocale(settings.assistantLanguage).language == "en" ->
                    "I couldn't find the city \"$location\". Try a more specific city name."
                cityNotFound -> error.message.orEmpty()
                assistantLocale(settings.assistantLanguage).language == "en" ->
                    "I couldn't get the latest weather. Check the internet connection and city name, then try again."
                else -> "Cuaca terkini belum bisa diambil. Periksa koneksi internet dan nama kota, lalu coba lagi."
            }
            Outcome(false, message)
        }
    }

    private suspend fun typeText(text: String, settings: AppSettings): Outcome {
        val english = assistantLocale(settings.assistantLanguage).language == "en"
        if (text.isBlank()) {
            return Outcome(false, if (english) "The text to type is empty." else "Teks yang akan diketik kosong.")
        }
        val result = textInserter.insert(text)
        val message = when {
            result.success && english -> "Text inserted at the active cursor."
            !result.success && english -> "Text was not inserted. Keep the target field focused and enable the Retrobit keyboard or accessibility service."
            else -> result.message
        }
        return Outcome(result.success, message)
    }

    private fun startDictation(settings: AppSettings): Outcome {
        val english = assistantLocale(settings.assistantLanguage).language == "en"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Outcome(false, if (english) "Microphone permission is required to start dictation." else "Izin mikrofon diperlukan untuk memulai dikte.")
        }
        return try {
            DictationService.start(context, DictationController.Source.OVERLAY)
            val message = if (english) {
                "Dictation started. The recognized text will be inserted at the active cursor."
            } else {
                "Dikte dimulai. Teks hasilnya akan disisipkan di posisi kursor aktif."
            }
            Outcome(true, message)
        } catch (error: Throwable) {
            VoiceLog.e("Tidak dapat memulai dikte dari asisten", error)
            Outcome(false, if (english) "Dictation could not start. Try the Dictation button in the app." else "Dikte tidak dapat dimulai. Coba gunakan tombol Dikte di aplikasi.")
        }
    }

    private fun showHelp(settings: AppSettings): Outcome {
        val message = if (assistantLocale(settings.assistantLanguage).language == "en") {
            "I can open apps, search only when asked, call phone contacts, prepare WhatsApp drafts, control media in the app on screen when screen context is enabled, set alarms and timers, save notes, control device settings, and report time, battery and city weather. Try: ‘play’ while a media app is open, ‘start dictation’, ‘type hello’, or ‘search …’."
        } else {
            "Saya bisa membuka aplikasi, mencari hanya jika diminta, menelepon kontak telepon, menyiapkan draf WhatsApp, mengontrol media sesuai aplikasi di layar jika konteks layar diaktifkan, membuat alarm dan timer, menyimpan catatan, mengatur perangkat, serta memberi tahu jam, baterai dan cuaca kota. Coba: ‘mainkan’ saat media terbuka, ‘dikte’, ‘ketik halo’, atau ‘cari …’."
        }
        return Outcome(true, message)
    }

    private fun assistantLocale(language: String): Locale =
        Locale.forLanguageTag(language).takeIf { it.language != "und" } ?: Locale.getDefault()

    private fun sendMessage(action: AssistantAction.SendMessage): Outcome {
        if (action.body.isBlank()) {
            return Outcome(false, "Isi pesan masih kosong; tidak ada draf yang dibuka")
        }
        var number: String? = null
        var display = action.contactName
        if (contacts.hasPermission()) {
            contacts.best(action.contactName)?.let {
                number = it.number
                display = it.displayName
            }
        }
        val body = action.body.ifBlank { "" }
        val encodedBody = URLEncoder.encode(body, "UTF-8")

        // 1) WhatsApp (paling umum di Indonesia)
        if (number != null) {
            val digits = number!!.filter { it.isDigit() }
            val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits?text=$encodedBody"))
                .setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (wa.resolveActivity(context.packageManager) != null) {
                context.startActivity(wa)
                return Outcome(true, "Draf WhatsApp untuk $display sudah dibuka. Periksa pesannya lalu tekan Kirim di WhatsApp untuk mengirim.")
            }
            // 2) SMS bawaan
            val sms = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                putExtra("sms_body", body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(sms)
                Outcome(true, "Draf SMS untuk $display sudah dibuka. Periksa pesannya lalu tekan Kirim di aplikasi pesan.")
            } catch (t: Throwable) {
                Outcome(false, "Tidak ada aplikasi pesan yang bisa dipakai")
            }
        }

        // Tanpa nomor kontak, izinkan pengguna memilih penerima di WhatsApp.
        // Ini tetap hanya membuka lembar draf; tombol Kirim tetap ditekan pengguna.
        val whatsappShare = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            setPackage("com.whatsapp")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (whatsappShare.resolveActivity(context.packageManager) != null) {
            try {
                context.startActivity(whatsappShare)
                return Outcome(true, "WhatsApp dibuka untuk memilih penerima. Periksa draf lalu tekan Kirim; pesan belum dikirim.")
            } catch (t: Throwable) {
                VoiceLog.w("WhatsApp tidak dapat membuka lembar berbagi: ${t.message}")
            }
        }

        // WhatsApp tidak terpasang → lempar ke aplikasi pesan yang tersedia.
        val generic = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(Intent.createChooser(generic, "Kirim pesan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(false, "Nomor $display tidak ditemukan. Pesan disiapkan untuk dipilih manual; belum dikirim.")
        } catch (t: Throwable) {
            Outcome(false, "Tidak bisa membuka aplikasi pesan")
        }
    }

    private suspend fun saveNote(body: String): Outcome {
        if (body.isBlank()) return Outcome(false, "Isi catatan kosong")
        history.appendNote(body)
        return Outcome(true, "Catatan disimpan: \"$body\"")
    }

    private fun setTorch(on: Boolean): Outcome {
        val manager = cameraManager ?: return Outcome(false, "Kamera tidak tersedia")
        return try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Outcome(false, "Perangkat ini tidak punya lampu kilat")
            manager.setTorchMode(cameraId, on)
            Outcome(true, if (on) "Senter dinyalakan." else "Senter dimatikan.")
        } catch (t: Throwable) {
            Outcome(false, "Tidak bisa mengubah senter: ${t.message}")
        }
    }

    private fun setVolume(action: AssistantAction.SetVolume): Outcome {
        val audio = audioManager ?: return Outcome(false, "AudioManager tidak tersedia")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return try {
            if (action.relative) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (action.percent >= 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            } else {
                val target = (action.percent.coerceIn(0, 100) * max / 100f).toInt()
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
            }
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
            Outcome(true, "Volume sekarang $now persen.")
        } catch (t: Throwable) {
            Outcome(false, "Tidak bisa mengubah volume")
        }
    }

    private fun setBrightness(action: AssistantAction.SetBrightness): Outcome {
        if (!Settings.System.canWrite(context)) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(permissionIntent)
                Outcome(false, "Izinkan Retrobit mengubah setelan sistem, lalu ulangi perintah kecerahan.")
            } catch (_: Throwable) {
                Outcome(false, "Izin ubah kecerahan belum aktif. Aktifkan ‘Ubah setelan sistem’ untuk Retrobit.")
            }
        }

        return try {
            val resolver = context.contentResolver
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(0, 255)
            val target = if (action.relative) {
                (current + action.percent * 255 / 100).coerceIn(0, 255)
            } else {
                (action.percent.coerceIn(0, 100) * 255 / 100)
            }
            val manual = Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val changed = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, target)
            if (!manual || !changed) return Outcome(false, "Setelan kecerahan tidak dapat diubah pada perangkat ini.")
            Outcome(true, "Kecerahan layar diatur ke ${target * 100 / 255} persen.")
        } catch (t: Throwable) {
            Outcome(false, "Tidak bisa mengubah kecerahan layar: ${t.message ?: "izin sistem ditolak"}")
        }
    }

    private fun setDnd(enabled: Boolean): Outcome {
        val notifications = context.getSystemService(NotificationManager::class.java)
            ?: return Outcome(false, "Layanan notifikasi tidak tersedia")
        if (!notifications.isNotificationPolicyAccessGranted) {
            val accessIntent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(accessIntent)
                Outcome(false, "Izinkan akses Jangan Ganggu untuk Retrobit, lalu ulangi perintah.")
            } catch (_: Throwable) {
                Outcome(false, "Aktifkan akses Jangan Ganggu untuk Retrobit di Pengaturan khusus.")
            }
        }
        return try {
            notifications.setInterruptionFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_NONE
                else NotificationManager.INTERRUPTION_FILTER_ALL,
            )
            Outcome(true, if (enabled) "Mode Jangan Ganggu dinyalakan." else "Mode Jangan Ganggu dimatikan.")
        } catch (t: Throwable) {
            Outcome(false, "Tidak bisa mengubah mode Jangan Ganggu: ${t.message ?: "akses sistem ditolak"}")
        }
    }

    private fun setBluetooth(enabled: Boolean): Outcome {
        val message = "Pengaturan Bluetooth dibuka. ${if (enabled) "Nyalakan" else "Matikan"} Bluetooth di sana untuk mengonfirmasi."
        return try {
            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Outcome(true, message)
        } catch (_: Throwable) {
            Outcome(false, "Pengaturan Bluetooth tidak tersedia di perangkat ini.")
        }
    }

    private fun answerCall(): Outcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return Outcome(false, "Menjawab panggilan lewat asisten memerlukan Android 8 atau yang lebih baru.")
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            val appSettings = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(appSettings)
                Outcome(false, "Izinkan izin Telepon untuk Retrobit di Pengaturan aplikasi, lalu ulangi perintah.")
            } catch (_: Throwable) {
                Outcome(false, "Izin Telepon diperlukan untuk menjawab panggilan masuk.")
            }
        }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return Outcome(false, "Layanan telepon tidak tersedia")
        return try {
            @Suppress("DEPRECATION")
            telecom.acceptRingingCall()
            Outcome(true, "Perintah untuk menjawab panggilan masuk dikirim ke sistem telepon.")
        } catch (t: Throwable) {
            Outcome(false, "Sistem tidak mengizinkan Retrobit menjawab panggilan ini: ${t.message ?: "izin ditolak"}")
        }
    }
}
