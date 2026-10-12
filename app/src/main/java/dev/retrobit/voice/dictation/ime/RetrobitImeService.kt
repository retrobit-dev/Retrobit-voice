package dev.retrobit.voice.dictation.ime

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.widget.TextView
import dev.retrobit.voice.MainActivity
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Keyboard dikte ala Wispr Flow.
 *
 * Keyboard ini sengaja minimalis: fokusnya satu hal — mengubah suara menjadi
 * teks dan langsung mengirimkannya ke kolom yang sedang aktif. Dipakai dengan
 * menekan ikon mikrofon, atau dibiarkan dalam mode "aktifkan lalu bicara".
 *
 * Setelah teks siap, [dev.retrobit.voice.dictation.insert.TextInserter] memakai
 * `commitText()` ke InputConnection milik keyboard ini, sehingga hasilnya masuk
 * persis di posisi kursor.
 */
class RetrobitImeService : InputMethodService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stateJob: Job? = null

    private var micButton: ImageButton? = null
    private var statusView: TextView? = null
    private var partialView: TextView? = null
    private var pasteButton: View? = null

    private var lastInsertedText: String = ""

    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.ime_bar, null)
        micButton = view.findViewById(R.id.ime_mic)
        statusView = view.findViewById(R.id.ime_status)
        partialView = view.findViewById(R.id.ime_partial)
        pasteButton = view.findViewById(R.id.ime_paste)

        micButton?.setOnClickListener { toggleDictation() }
        pasteButton?.setOnClickListener {
            if (lastInsertedText.isNotBlank()) {
                currentInputConnection?.commitText(lastInsertedText, 1)
                statusView?.text = getString(R.string.ime_repasted)
            }
        }
        view.findViewById<View>(R.id.ime_close).setOnClickListener { requestHideSelf(0) }
        view.findViewById<View>(R.id.ime_keyboard).setOnClickListener {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            }
            imm?.showInputMethodPicker()
        }
        view.findViewById<View>(R.id.ime_settings).setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
        view.findViewById<View>(R.id.ime_delete).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        view.findViewById<View>(R.id.ime_space).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        view.findViewById<View>(R.id.ime_enter).setOnClickListener {
            val ic = currentInputConnection
            val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
            val handled = when (action) {
                EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEARCH,
                -> ic?.performEditorAction(action) ?: false
                else -> false
            }
            if (handled != true) ic?.commitText("\n", 1)
        }

        observeState()
        return view
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            AppGraph.require.dictation.state.collect { state ->
                val listening = state.phase == DictationController.Phase.LISTENING
                micButton?.isSelected = listening
                micButton?.contentDescription = getString(
                    if (listening) R.string.ime_mic_stop else R.string.ime_mic_start,
                )

                statusView?.text = when {
                    state.needsMicrophonePermission -> getString(R.string.ime_need_permission)
                    state.error != null -> state.error
                    state.stage.isNotBlank() -> state.stage
                    state.finalText.isNotBlank() -> getString(R.string.ime_done)
                    else -> getString(R.string.ime_hint)
                }

                partialView?.text = when {
                    state.partial.isNotBlank() -> state.partial
                    state.finalText.isNotBlank() -> state.finalText
                    else -> ""
                }
                partialView?.visibility = if (partialView?.text.isNullOrBlank()) View.GONE else View.VISIBLE

                if (state.phase == DictationController.Phase.DONE && state.finalText.isNotBlank()) {
                    lastInsertedText = state.finalText
                }
                pasteButton?.visibility =
                    if (lastInsertedText.isNotBlank() && !state.isBusy) View.VISIBLE else View.GONE
            }
        }
    }

    private fun toggleDictation() {
        val graph = AppGraph.require
        val phase = graph.dictation.state.value.phase
        when (phase) {
            DictationController.Phase.LISTENING -> DictationService.stop(this)
            DictationController.Phase.TRANSCRIBING, DictationController.Phase.CLEANING, DictationController.Phase.INSERTING -> {
                VoiceLog.d("Masih memproses, tunggu sebentar")
            }
            else -> {
                graph.dictation.reset()
                DictationService.start(this, DictationController.Source.IME)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        active = WeakReference(this)
        statusView?.text = getString(R.string.ime_hint)
    }

    override fun onFinishInput() {
        super.onFinishInput()
        // Jangan hentikan dikte yang sedang berjalan: pengguna bisa memindah fokus
        // dan tetap ingin teksnya ditempel di kolom baru.
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onDestroy() {
        stateJob?.cancel()
        scope.cancel()
        if (active?.get() === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var active: WeakReference<RetrobitImeService>? = null

        fun activeService(): RetrobitImeService? = active?.get()

        fun currentConnection(): InputConnection? = active?.get()?.currentInputConnection

        fun hasActiveConnection(): Boolean = currentConnection() != null
    }
}
