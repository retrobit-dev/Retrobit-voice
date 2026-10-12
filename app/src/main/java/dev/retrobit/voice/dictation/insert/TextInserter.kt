package dev.retrobit.voice.dictation.insert

import android.content.Context
import android.view.inputmethod.InputConnection
import android.widget.Toast
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.InsertMethod
import dev.retrobit.voice.data.SettingsRepository
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.ime.RetrobitImeService
import kotlinx.coroutines.flow.first

/**
 * Menempelkan teks hasil dikte ke aplikasi yang sedang dipakai.
 *
 * Tiga jalur, dipilih otomatis (atau dipaksa dari Pengaturan):
 *  1. [RetrobitImeService]  — pengguna memakai keyboard Retrobit → tempel langsung, paling andal.
 *  2. [TextInsertAccessibilityService] — pengguna mengaktifkan layanan aksesibilitas → kolom
 *     teks mana pun bisa diisi otomatis (auto-paste dari papan klip).
 *  3. Papan klip            — selalu tersedia sebagai jaring pengaman + notifikasi.
 */
class TextInserter(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    enum class Channel(val label: String) {
        IME("keyboard Retrobit"),
        ACCESSIBILITY("layanan aksesibilitas"),
        CLIPBOARD("papan klip"),
    }

    data class Result(val success: Boolean, val channel: Channel, val message: String)

    fun imeReady(): Boolean = RetrobitImeService.hasActiveConnection()
    fun accessibilityReady(): Boolean = TextInsertAccessibilityService.isConnected()

    /** Deskripsi jalur yang saat ini akan dipakai — ditampilkan di UI. */
    fun describeAvailable(): String = buildList {
        if (imeReady()) add("keyboard")
        if (accessibilityReady()) add("aksesibilitas")
        add("papan klip")
    }.joinToString(" → ")

    suspend fun insert(text: String): Result {
        if (text.isBlank()) return Result(false, Channel.CLIPBOARD, "Tidak ada teks untuk ditempel")
        val method = settings.flow.first().insertMethod

        val order = when (method) {
            InsertMethod.IME -> listOf(Channel.IME, Channel.CLIPBOARD)
            InsertMethod.ACCESSIBILITY -> listOf(Channel.ACCESSIBILITY, Channel.CLIPBOARD)
            InsertMethod.CLIPBOARD -> listOf(Channel.CLIPBOARD)
            InsertMethod.AUTO -> listOf(Channel.IME, Channel.ACCESSIBILITY, Channel.CLIPBOARD)
        }

        for (channel in order) {
            when (channel) {
                Channel.IME -> if (insertViaIme(text)) {
                    return Result(true, Channel.IME, "Teks langsung masuk ke kolom yang Anda tulis")
                }

                Channel.ACCESSIBILITY -> if (TextInsertAccessibilityService.insertIntoFocusedField(context, text)) {
                    return Result(true, Channel.ACCESSIBILITY, "Teks ditempel otomatis lewat aksesibilitas")
                }

                Channel.CLIPBOARD -> {
                    val copied = ClipboardHelper.copy(context, text)
                    return if (copied) {
                        toast("Disalin ke papan klip — tempel dengan lama tekan")
                        Result(true, Channel.CLIPBOARD, "Teks disalin ke papan klip")
                    } else {
                        Result(false, Channel.CLIPBOARD, "Gagal menyalin teks")
                    }
                }
            }
        }
        return Result(false, Channel.CLIPBOARD, "Tidak ada jalur penempelan yang tersedia")
    }

    private fun insertViaIme(text: String): Boolean {
        val connection: InputConnection = RetrobitImeService.currentConnection() ?: return false
        return try {
            connection.commitText(text, 1)
            true
        } catch (t: Throwable) {
            VoiceLog.e("commitText gagal", t)
            false
        }
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
