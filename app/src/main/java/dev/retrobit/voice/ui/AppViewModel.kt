package dev.retrobit.voice.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import dev.retrobit.voice.dictation.overlay.OverlayBubbleService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Satu ViewModel untuk seluruh aplikasi. Aplikasi ini cukup kecil sehingga
 * memakai satu sumber status lebih mudah dipahami daripada banyak ViewModel.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph: AppGraph = AppGraph.init(application)

    val settings: StateFlow<AppSettings?> = run {
        val flow = MutableStateFlow<AppSettings?>(null)
        viewModelScope.launch { graph.settings.flow.collect { flow.value = it } }
        flow.asStateFlow()
    }

    val dictation: StateFlow<DictationController.UiState> = graph.dictation.state
    val assistant: StateFlow<dev.retrobit.voice.assistant.AssistantSession.UiState> = graph.assistant.state
    val assistantHistory = graph.assistant.history
    val dictationHistory = graph.history.dictation
    val log = MutableStateFlow("")

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch { graph.history.load() }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { graph.settings.update(transform) }
    }

    // ---------------- Dikte ----------------

    fun startDictation(source: DictationController.Source = DictationController.Source.MANUAL) {
        graph.dictation.reset()
        DictationService.start(getApplication(), source)
    }

    fun stopDictation() = DictationService.stop(getApplication())

    fun cancelDictation() {
        graph.dictation.cancel()
        DictationService.stop(getApplication())
    }

    fun copyLastResult() {
        val text = graph.dictation.state.value.finalText
        if (text.isBlank()) return
        dev.retrobit.voice.dictation.insert.ClipboardHelper.copy(getApplication(), text)
        _toast.value = "Teks disalin ke papan klip"
    }

    fun shareText(text: String) {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            getApplication<Application>().startActivity(
                Intent.createChooser(intent, "Bagikan teks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------- Asisten ----------------

    fun sendToAssistant(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { graph.assistant.handleText(text) }
    }

    fun listenToAssistant() {
        viewModelScope.launch { graph.assistant.listenAndHandle() }
    }

    fun clearAssistantHistory() {
        graph.assistant.clearHistory()
        viewModelScope.launch { graph.settings.clearHistory() }
    }

    // ---------------- Bubble melayang ----------------

    fun startOverlayBubble() {
        if (!OverlayBubbleService.hasPermission(getApplication())) {
            _toast.value = "Izinkan \"tampil di atas aplikasi lain\" dulu untuk bubble melayang"
            return
        }
        OverlayBubbleService.start(getApplication())
        update { it.copy(overlayBubbleEnabled = true) }
    }

    fun stopOverlayBubble() {
        OverlayBubbleService.stop(getApplication())
        update { it.copy(overlayBubbleEnabled = false) }
    }

    // ---------------- Diagnostik ----------------

    fun refreshLog() {
        log.value = dev.retrobit.voice.core.VoiceLog.snapshot()
    }

    fun consumeToast() {
        _toast.value = null
    }

    fun sttStatusSummary(): String {
        val current = settings.value ?: AppSettings()
        return graph.stt.statusSummary(current)
    }

    fun appGraph(): AppGraph = graph
}
