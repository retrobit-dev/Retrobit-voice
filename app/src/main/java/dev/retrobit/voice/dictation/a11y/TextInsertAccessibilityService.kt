package dev.retrobit.voice.dictation.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.insert.ClipboardHelper
import dev.retrobit.voice.dictation.VolumeKeyTrigger
import java.lang.ref.WeakReference

/**
 * Layanan aksesibilitas opsional — ini yang membuat dikte bisa "menempel sendiri"
 * di aplikasi mana pun tanpa memakai keyboard Retrobit (mirip perilaku Wispr Flow).
 *
 * Cara kerjanya sengaja sesederhana mungkin agar aman dan dapat diprediksi:
 *   1. salin teks ke papan klip,
 *   2. kirim ACTION_PASTE ke kolom yang sedang difokuskan.
 * Kalau ACTION_PASTE tidak didukung aplikasi tujuan, dipakai ACTION_SET_TEXT.
 * Kalau keduanya gagal, pengguna cukup menempel manual (teksnya sudah di klip).
 *
 * Layanan ini TIDAK membaca isi layar dan TIDAK mengirim data ke mana pun.
 */
class TextInsertAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        // Sumber peristiwa tombol volume: dikte bisa dipicu tanpa membuka aplikasi.
        VolumeKeyTrigger.attach()
        VoiceLog.i("Layanan aksesibilitas Retrobit aktif")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Tidak ada yang perlu dipantau — hanya bertindak saat diminta.
    }

    /**
     * Peristiwa tombol volume (flag `flagRequestFilterKeyEvents` di
     * accessibility_service_config.xml). Mengembalikan true = tombol ditelan,
     * jadi volume tidak ikut berubah selama tombol dipakai untuk dikte.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        val key = event ?: return false
        if (VolumeKeyTrigger.onKeyEvent(this, key)) return true
        return super.onKeyEvent(key)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    /** Sisipkan teks ke kolom yang sedang difokuskan. */
    private fun insert(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            ?: return false

        if (!node.isEditable) return false

        // Langkah 1: tempel dari papan klip (paling aman untuk berbagai aplikasi,
        // termasuk yang memakai WebView seperti Gmail/WhatsApp Web/Chrome).
        ClipboardHelper.copy(this, text, toast = null)
        val pasted = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_PASTE) }.getOrDefault(false)
        if (pasted) return true

        // Langkah 2: set teks langsung (hanya kalau kolom bisa dibaca).
        val existing = runCatching { node.text?.toString() ?: "" }.getOrDefault("")
        val combined = existing + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined)
        }
        return runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }.getOrDefault(false)
    }

    companion object {
        @Volatile
        private var instance: WeakReference<TextInsertAccessibilityService>? = null

        fun isConnected(): Boolean = instance?.get() != null

        /**
         * Dipanggil dari mana saja. Mengembalikan true kalau teks berhasil
         * disisipkan ke kolom input aplikasi lain.
         */
        fun insertIntoFocusedField(context: android.content.Context, text: String): Boolean {
            val service = instance?.get()
            if (service == null) {
                // Pastikan teks setidaknya berada di papan klip.
                ClipboardHelper.copy(context, text)
                return false
            }
            return try {
                service.insert(text)
            } catch (t: Throwable) {
                VoiceLog.e("Aksesibilitas gagal menyisipkan teks", t)
                ClipboardHelper.copy(context, text)
                false
            }
        }
    }
}
