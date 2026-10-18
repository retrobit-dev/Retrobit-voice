package dev.retrobit.voice.dictation.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.VolumeKeyTrigger
import dev.retrobit.voice.dictation.insert.TextInsertionPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Integrasi aksesibilitas yang bertindak hanya saat diminta:
 * - mendeteksi apakah kolom teks sedang mendapat fokus agar bubble bisa disembunyikan,
 * - menyisipkan teks langsung ke kolom aktif (tanpa clipboard),
 * - membaca tombol volume bila salah satu pemicu volume diaktifkan, dan
 * - membaca teks layar satu kali hanya bila pengguna mengaktifkan opt-in konteks layar.
 *
 * Isi layar tidak dipantau atau disimpan di sini. Pemindaian teks hanya berlangsung
 * saat [readActiveScreenContext] dipanggil oleh sesi asisten.
 */
class TextInsertAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshFocus = Runnable { updateFocusedInput() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = WeakReference(this)
        VolumeKeyTrigger.attach()
        updateFocusedInput()
        VoiceLog.i("Layanan aksesibilitas Retrobit aktif")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Jangan biarkan bubble tertinggal di aplikasi sebelumnya ketika
            // pengguna berpindah jendela; fokus input diperiksa ulang sesudahnya.
            _focusedTextField.value = false
        } else {
            val source = runCatching { event.source }.getOrNull()
            if (source?.isEditable == true && source.isFocused) {
                _focusedTextField.value = true
            }
        }

        // Event fokus dan perubahan seleksi bisa datang sebelum pohon jendela
        // diperbarui. Debounce sebentar lalu tanya langsung node yang menerima input.
        mainHandler.removeCallbacks(refreshFocus)
        mainHandler.postDelayed(refreshFocus, FOCUS_REFRESH_DELAY_MS)
    }

    /**
     * Peristiwa tombol volume (flag `flagRequestFilterKeyEvents` di konfigurasi).
     * Mengembalikan true berarti tombol ditelan dan tidak diteruskan ke sistem.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean =
        VolumeKeyTrigger.onKeyEvent(this, event) || super.onKeyEvent(event)

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshFocus)
        if (instance?.get() === this) {
            instance = null
            _focusedTextField.value = false
        }
        super.onDestroy()
    }

    private fun updateFocusedInput() {
        val focused = runCatching {
            val root = rootInActiveWindow ?: return@runCatching false
            val input = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@runCatching false
            input.isEditable && !input.isPassword
        }.getOrDefault(false)
        _focusedTextField.value = focused
    }

    /** Sisipkan teks pada seleksi/kursor aktif, mempertahankan teks di sekitarnya. */
    private fun insertAtSelection(text: String): Boolean {
        if (text.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.isEditable || node.isPassword) return false

        val rawExisting = runCatching { node.text?.toString().orEmpty() }.getOrElse { return false }
        val existing = TextInsertionPlan.contentWithoutPlaceholder(
            existing = rawExisting,
            hintText = node.hintText?.toString(),
            contentDescription = node.contentDescription?.toString(),
        )
        val placeholderWasExposedAsText = existing != rawExisting
        val insertion = TextInsertionPlan.insert(
            existing = existing,
            selectionStart = if (placeholderWasExposedAsText) 0 else node.textSelectionStart,
            selectionEnd = if (placeholderWasExposedAsText) 0 else node.textSelectionEnd,
            insertion = text,
        )

        val setTextArgs = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, insertion.text)
        }
        val changed = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs)
        }.getOrDefault(false)
        if (!changed) return false

        // ACTION_SET_TEXT biasanya memindahkan caret ke akhir. Atur ulang ke tepat
        // setelah sisipan agar dikte berikutnya tetap masuk di posisi kursor yang benar.
        val cursor = insertion.cursor
        val selectionArgs = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        val selectionRestored = runCatching {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
        }.getOrDefault(false)
        if (!selectionRestored) {
            VoiceLog.d("Kolom menerima teks, tetapi tidak mendukung pemulihan posisi kursor")
        }
        return true
    }

    /** Baca teks node yang terlihat sekarang. Dipanggil hanya atas permintaan asisten. */
    private fun captureVisibleScreenContext(): String? {
        val root = rootInActiveWindow ?: return null
        val sourcePackage = root.packageName?.toString().orEmpty()
        if (sourcePackage.isBlank() || sourcePackage == packageName) return null

        val visibleText = linkedSetOf<String>()
        var totalChars = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_TREE_DEPTH || visibleText.size >= MAX_TEXT_NODES || totalChars >= MAX_CONTEXT_CHARS) return
            if (!runCatching { node.isVisibleToUser }.getOrDefault(false)) return
            if (runCatching { node.isPassword }.getOrDefault(true)) return

            // Jangan ikutkan isi yang sedang diketik pengguna atau anak node-nya.
            if (runCatching { node.isEditable }.getOrDefault(false)) return
            val text = runCatching { node.text?.toString().orEmpty() }.getOrDefault("")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (text.isNotBlank() && text !in visibleText) {
                val clipped = text.take(MAX_CONTEXT_CHARS - totalChars)
                if (clipped.isNotBlank()) {
                    visibleText += clipped
                    totalChars += clipped.length
                }
            }

            for (index in 0 until node.childCount) {
                if (totalChars >= MAX_CONTEXT_CHARS || visibleText.size >= MAX_TEXT_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                visit(child, depth + 1)
            }
        }

        visit(root, 0)
        return buildString {
            append("Aplikasi: ").append(sourcePackage).append('\n')
            visibleText.forEach { append("- ").append(it).append('\n') }
        }.trim().take(MAX_CONTEXT_CHARS + 120)
    }

    companion object {
        private const val FOCUS_REFRESH_DELAY_MS = 80L
        private const val MAX_CONTEXT_CHARS = 2_400
        private const val MAX_TEXT_NODES = 100
        private const val MAX_TREE_DEPTH = 24

        @Volatile
        private var instance: WeakReference<TextInsertAccessibilityService>? = null

        private val _focusedTextField = MutableStateFlow(false)
        val focusedTextField: StateFlow<Boolean> = _focusedTextField.asStateFlow()

        fun isConnected(): Boolean = instance?.get() != null

        fun hasFocusedTextField(): Boolean = _focusedTextField.value

        /**
         * Sisipkan tanpa papan klip. false berarti jalur IME langsung mungkin
         * masih bisa digunakan, tetapi fallback salin/tempel sengaja tidak dilakukan.
         */
        fun insertIntoFocusedField(text: String): Boolean {
            val service = instance?.get() ?: return false
            return try {
                service.insertAtSelection(text)
            } catch (t: Throwable) {
                VoiceLog.e("Aksesibilitas gagal menyisipkan teks langsung", t)
                false
            }
        }

        /** Snapshot satu kali atas permintaan eksplisit; tidak merekam perubahan berikutnya. */
        suspend fun readActiveScreenContext(): String? = withContext(Dispatchers.Main.immediate) {
            val service = instance?.get() ?: return@withContext null
            runCatching { service.captureVisibleScreenContext() }
                .onFailure { VoiceLog.e("Gagal membaca konteks layar yang diminta", it) }
                .getOrNull()
        }
    }
}
