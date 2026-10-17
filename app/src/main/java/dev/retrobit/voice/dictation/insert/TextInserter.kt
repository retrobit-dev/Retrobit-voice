package dev.retrobit.voice.dictation.insert

import android.view.inputmethod.InputConnection
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.InsertMethod
import dev.retrobit.voice.data.SettingsRepository
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.ime.RetrobitImeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Menyisipkan hasil dikte langsung ke posisi kursor aktif.
 *
 * Jalur yang didukung adalah `InputConnection.commitText()` dari keyboard Retrobit
 * atau aksi teks aksesibilitas pada kolom yang sedang fokus. Tidak ada fallback
 * clipboard: jika kedua jalur langsung tidak tersedia, pengguna diberi tahu bahwa
 * teks belum disisipkan.
 */
class TextInserter(
    private val settings: SettingsRepository,
) {

    enum class Channel(val label: String) {
        IME("keyboard Retrobit"),
        ACCESSIBILITY("layanan aksesibilitas"),
        NONE("tidak ada jalur langsung"),
    }

    data class Result(val success: Boolean, val channel: Channel, val message: String)

    fun imeReady(): Boolean = RetrobitImeService.hasActiveConnection()
    fun accessibilityReady(): Boolean = TextInsertAccessibilityService.isConnected()

    /** Jalur penyisipan langsung yang saat ini tersedia. */
    fun describeAvailable(): String = buildList {
        if (imeReady()) add("keyboard")
        if (accessibilityReady()) add("aksesibilitas")
    }.ifEmpty { listOf("tidak ada (aktifkan keyboard atau aksesibilitas)") }.joinToString(" → ")

    suspend fun insert(text: String): Result {
        if (text.isBlank()) return Result(false, Channel.NONE, "Tidak ada teks untuk disisipkan")
        val method = settings.flow.first().insertMethod
        return withContext(Dispatchers.Main.immediate) {
            val order = when (method) {
                InsertMethod.IME -> listOf(Channel.IME, Channel.ACCESSIBILITY)
                InsertMethod.ACCESSIBILITY -> listOf(Channel.ACCESSIBILITY, Channel.IME)
                InsertMethod.AUTO -> listOf(Channel.IME, Channel.ACCESSIBILITY)
            }

            for (channel in order) {
                val inserted = when (channel) {
                    Channel.IME -> insertViaIme(text)
                    Channel.ACCESSIBILITY -> TextInsertAccessibilityService.insertIntoFocusedField(text)
                    Channel.NONE -> false
                }
                if (inserted) {
                    val message = when (channel) {
                        Channel.IME -> "Teks disisipkan langsung di posisi kursor lewat keyboard Retrobit"
                        Channel.ACCESSIBILITY -> "Teks disisipkan langsung di posisi kursor lewat aksesibilitas"
                        Channel.NONE -> "Teks belum disisipkan"
                    }
                    return@withContext Result(true, channel, message)
                }
            }

            Result(
                success = false,
                channel = Channel.NONE,
                message = "Tidak dapat menyisipkan teks langsung. Pastikan kolom teks tetap fokus dan aktifkan keyboard Retrobit atau layanan aksesibilitas.",
            )
        }
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
}
