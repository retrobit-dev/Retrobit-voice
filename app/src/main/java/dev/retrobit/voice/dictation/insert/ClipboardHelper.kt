package dev.retrobit.voice.dictation.insert

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import dev.retrobit.voice.core.VoiceLog

/** Utilitas papan klip — jalur terakhir yang selalu berhasil di Android mana pun. */
object ClipboardHelper {

    fun copy(context: Context, text: String, toast: String? = "Teks disalin — tempel di mana saja"): Boolean {
        if (text.isBlank()) return false
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Retrobit Voice", text))
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                // Sejak Android 13 sistem sendiri menampilkan pratinjau salinan.
                toast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
            }
            true
        } catch (t: Throwable) {
            VoiceLog.e("Gagal menyalin ke papan klip", t)
            false
        }
    }
}
