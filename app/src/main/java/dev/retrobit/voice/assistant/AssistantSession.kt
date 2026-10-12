package dev.retrobit.voice.assistant

import android.content.Context
import android.content.Intent
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.AssistantTurn
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sesi asisten: menerima kalimat (dari suara atau teks), menjalankan aksi,
 * membacakan jawaban, dan menyimpan percakapan.
 *
 * Dipakai oleh layar utama, bubble, wake word, tile Pengaturan Cepat, dan
 * VoiceInteractionService (saat Retrobit dijadikan asisten digital bawaan).
 */
class AssistantSession(
    private val context: Context,
    private val graph: AppGraph,
) {

    data class UiState(
        val listening: Boolean = false,
        val thinking: Boolean = false,
        val partial: String = "",
        val status: String = "",
        val lastTurn: AssistantTurn? = null,
        val error: String? = null,
        val needsMicrophonePermission: Boolean = false,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val history: StateFlow<List<AssistantTurn>> = turns.asStateFlow()

    private var wakeWordListener: WakeWordListener? = null

    /** Proses satu kalimat: pahami → jalankan → jawab. */
    suspend fun handleText(text: String, speakReply: Boolean? = null): AssistantTurn {
        val settings = graph.settings.current()
        _state.value = _state.value.copy(thinking = true, status = "Memproses…", error = null, partial = "")

        val understanding = try {
            graph.brain.understand(
                userText = text,
                settings = settings,
                history = turns.value.takeLast(6).flatMap {
                    listOf("user" to it.userText, "assistant" to it.reply)
                },
            )
        } catch (t: Throwable) {
            VoiceLog.e("Pemahaman gagal", t)
            _state.value = _state.value.copy(thinking = false, error = t.message ?: "Gagal memahami perintah")
            return AssistantTurn(text, null, "Maaf, terjadi kesalahan saat memproses.", "error")
        }

        var reply = understanding.reply
        understanding.action?.let { action ->
            val outcome = graph.assistantExecutor.execute(action, settings)
            if (!outcome.success && outcome.message.isNotBlank()) reply = outcome.message
            else if (outcome.message.isNotBlank()) reply = outcome.message
        }

        val turn = AssistantTurn(
            userText = text,
            action = understanding.action,
            reply = reply,
            source = understanding.source,
        )

        turns.value = (turns.value + turn).takeLast(50)
        graph.history.addAssistant(turn, settings.saveHistory)

        val shouldSpeak = speakReply ?: settings.speakReplies
        if (shouldSpeak) graph.tts.speak(reply, settings.assistantLanguage)

        _state.value = _state.value.copy(thinking = false, status = reply, lastTurn = turn)
        return turn
    }

    /** Dengarkan sekali, lalu proses (dipakai setelah wake word / dari bubble). */
    suspend fun listenAndHandle(speakReply: Boolean? = null) {
        val settings = graph.settings.current()
        _state.value = _state.value.copy(listening = true, partial = "", status = "Mendengarkan…", error = null)
        try {
            val result = graph.dictation.listenForText(settings)
            _state.value = _state.value.copy(listening = false)
            result.onSuccess { text ->
                if (text.isNotBlank()) handleText(text, speakReply)
            }.onFailure { error ->
                val needsPermission = !graph.dictation.hasMicrophonePermission()
                _state.value = _state.value.copy(
                    listening = false,
                    needsMicrophonePermission = needsPermission,
                    status = "",
                    error = error.message ?: "Tidak mendengar apa pun",
                )
            }
        } catch (t: Throwable) {
            VoiceLog.e("Sesi asisten gagal", t)
            _state.value = _state.value.copy(listening = false, error = t.message)
        }
    }

    fun startWakeWordIfEnabled() {
        scope.launch {
            val settings = graph.settings.current()
            if (!settings.wakeWordEnabled) {
                stopWakeWord()
                return@launch
            }
            if (wakeWordListener != null) return@launch
            wakeWordListener = WakeWordListener(
                context = context,
                phrase = settings.wakeWordPhrase,
                onWake = { heard ->
                    _state.value = _state.value.copy(status = "Kata kunci terdeteksi: \"$heard\"")
                    scope.launch { listenAndHandle() }
                },
                onStateChange = { status -> _state.value = _state.value.copy(status = status) },
            ).also {
                it.start(scope, settings.assistantLanguage, preferOnDevice = true)
            }
        }
    }

    fun stopWakeWord() {
        wakeWordListener?.stop()
        wakeWordListener = null
    }

    /** Buka layar asisten dari luar (bubble, tile, wake word). */
    fun openAssistantUi(autoListen: Boolean) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, autoListen)
        }
        withContextNoThrow { context.startActivity(intent) }
    }

    private fun withContextNoThrow(block: () -> Unit) {
        runCatching { block() }.onFailure { VoiceLog.e("Gagal membuka UI asisten", it) }
    }

    fun clearHistory() {
        turns.value = emptyList()
    }

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message)
    }

    fun currentSettings(): AppSettings = kotlinx.coroutines.runBlocking { graph.settings.current() }

    fun shutdown() {
        stopWakeWord()
        graph.tts.shutdown()
    }
}
