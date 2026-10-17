package dev.retrobit.voice.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.retrobit.voice.BuildConfig
import dev.retrobit.voice.R
import dev.retrobit.voice.assistant.WakeWordService
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.InvalidSnapshotException
import dev.retrobit.voice.data.decodeSettingsSnapshot
import dev.retrobit.voice.data.describe
import dev.retrobit.voice.data.encode
import dev.retrobit.voice.data.toSettings
import dev.retrobit.voice.data.toSnapshot
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.DictationService
import dev.retrobit.voice.dictation.overlay.OverlayBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var wakeWordSyncJob: Job? = null

    init {
        viewModelScope.launch { graph.history.load() }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            var wakeWordShouldSync = false
            var restartWakeWord = false
            graph.settings.update { current ->
                val updated = transform(current)
                wakeWordShouldSync = current.wakeWordEnabled != updated.wakeWordEnabled ||
                    (updated.wakeWordEnabled && (
                        current.wakeWordPhrase != updated.wakeWordPhrase ||
                            current.assistantLanguage != updated.assistantLanguage
                        ))
                restartWakeWord = current.wakeWordEnabled && updated.wakeWordEnabled && wakeWordShouldSync
                updated
            }
            if (wakeWordShouldSync) {
                wakeWordSyncJob?.cancel()
                if (restartWakeWord) {
                    // Frasa ditulis per karakter; tunggu jeda singkat sebelum
                    // membongkar dan memulai ulang recognizer.
                    wakeWordSyncJob = viewModelScope.launch {
                        delay(WAKE_WORD_RESTART_DEBOUNCE_MS)
                        graph.assistant.stopWakeWord()
                        synchronizeWakeWordService()
                    }
                } else {
                    wakeWordSyncJob = null
                    synchronizeWakeWordService()
                }
            }
        }
    }

    /** Sinkronkan layanan foreground setelah activity kembali ke depan. */
    fun syncWakeWordService() {
        viewModelScope.launch { synchronizeWakeWordService() }
    }

    private suspend fun synchronizeWakeWordService() {
        val app = getApplication<Application>()
        val current = graph.settings.current()
        if (!current.wakeWordEnabled) {
            WakeWordService.stop(app)
            return
        }
        if (!graph.dictation.hasMicrophonePermission()) {
            graph.settings.update { it.copy(wakeWordEnabled = false) }
            WakeWordService.stop(app)
            _toast.value = app.getString(R.string.settings_wake_word_needs_microphone)
            return
        }
        if (!WakeWordService.start(app)) {
            _toast.value = app.getString(R.string.toast_wake_word_start_failed)
        }
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

    fun confirmPendingAssistantMessage() {
        viewModelScope.launch { graph.assistant.confirmPendingMessage() }
    }

    fun cancelPendingAssistantMessage() {
        viewModelScope.launch { graph.assistant.cancelPendingMessage() }
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

    // ---------------- Cadangan setelan ----------------

    /**
     * Tulis setelan aktif ke berkas yang dipilih pengguna (lewat SAF).
     *
     * Terutama berguna untuk memindahkan API key: kunci Android Keystore ikut
     * terhapus saat aplikasi di-uninstall, jadi berkas inilah satu-satunya cara
     * memulihkan setelan tanpa mengetik ulang dari awal.
     */
    fun exportSettingsTo(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val hasil = runCatching {
                val current = graph.settings.current()
                withContext(Dispatchers.IO) {
                    val text = current.toSnapshot(versionName = BuildConfig.VERSION_NAME).encode()
                    val out = app.contentResolver.openOutputStream(uri, "wt")
                        ?: error("Tidak bisa membuka berkas tujuan")
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
            }
            hasil.exceptionOrNull()?.let { VoiceLog.e("Ekspor setelan gagal", it) }
            _toast.value = app.getString(
                if (hasil.isSuccess) R.string.toast_export_done else R.string.toast_export_failed,
            )
        }
    }

    /** Baca berkas cadangan lalu terapkan isinya ke setelan aktif. */
    fun importSettingsFrom(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val hasil = runCatching {
                val text = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: error("Tidak bisa membuka berkas cadangan")
                    input.use { String(it.readBytes(), Charsets.UTF_8) }
                }
                val snapshot = decodeSettingsSnapshot(text)
                // update() menulis ulang lewat SecretStore, jadi API key dari berkas
                // otomatis dienkripsi lagi — bukan disimpan mentah.
                graph.settings.update { current -> snapshot.toSettings(current) }
                graph.assistant.stopWakeWord()
                synchronizeWakeWordService()
                app.getString(R.string.toast_import_done, snapshot.describe())
            }
            hasil.exceptionOrNull()?.let { VoiceLog.e("Impor setelan gagal", it) }
            _toast.value = hasil.getOrElse { t ->
                if (t is InvalidSnapshotException) {
                    t.message ?: app.getString(R.string.toast_import_invalid)
                } else {
                    app.getString(R.string.toast_import_failed)
                }
            }
        }
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

    private companion object {
        const val WAKE_WORD_RESTART_DEBOUNCE_MS = 450L
    }
}
