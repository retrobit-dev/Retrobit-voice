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
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.HistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

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
) {

    data class Outcome(
        val success: Boolean,
        val message: String,
        val needsPermission: String? = null,
    )

    private val apps = AppResolver(context)
    private val contacts = ContactsResolver(context)
    private val cameraManager by lazy { context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }

    fun installedAppNames(): List<String> = apps.installedApps()

    suspend fun execute(action: AssistantAction, settings: AppSettings): Outcome = withContext(Dispatchers.Main) {
        try {
            when (action) {
                is AssistantAction.OpenApp -> openApp(action.appQuery)
                is AssistantAction.WebSearch -> webSearch(action.query, action.site)
                is AssistantAction.Dial -> dial(action.target, action.contactName)
                is AssistantAction.CallContact -> callContact(action.name)
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

    private fun callContact(name: String): Outcome {
        if (!contacts.hasPermission()) {
            return Outcome(false, "Butuh izin kontak untuk menelepon $name", Manifest.permission.READ_CONTACTS)
        }
        val contact = contacts.best(name)
            ?: return Outcome(false, "Kontak \"$name\" tidak ditemukan di buku telepon")
        return dial(contact.number, contact.displayName)
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
        val message = "Panel Bluetooth dibuka. ${if (enabled) "Nyalakan" else "Matikan"} Bluetooth di panel sistem untuk mengonfirmasi."
        val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent(Settings.Panel.ACTION_BLUETOOTH)
        } else {
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(panelIntent)
            Outcome(true, message)
        } catch (_: Throwable) {
            val fallback = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
                Outcome(true, message)
            } catch (_: Throwable) {
                Outcome(false, "Pengaturan Bluetooth tidak tersedia di perangkat ini.")
            }
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
