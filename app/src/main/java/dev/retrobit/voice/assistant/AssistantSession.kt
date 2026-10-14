package dev.retrobit.voice.assistant

import android.content.Context
import android.content.Intent
import dev.retrobit.voice.MainActivity
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.AssistantAction
import dev.retrobit.voice.core.AssistantTurn
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.SttEngineChoice
import dev.retrobit.voice.dictation.DictationController
import dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService
import dev.retrobit.voice.dictation.overlay.OverlayBubbleService
import dev.retrobit.voice.stt.RecognizerGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
        /** Kalimat aktif yang sedang dipahami, ditampilkan pada popup asisten. */
        val currentCommand: String = "",
        /** Jawaban giliran aktif, ditampilkan pada popup asisten. */
        val currentReply: String = "",
        val status: String = "",
        val lastTurn: AssistantTurn? = null,
        val error: String? = null,
        val needsMicrophonePermission: Boolean = false,
        /** Pesan yang menunggu persetujuan eksplisit. Tidak dieksekusi sebelum konfirmasi. */
        val pendingMessage: AssistantAction.SendMessage? = null,
        val pendingMessageCreatedAtMs: Long? = null,
        val pendingMessageUsesScreenContext: Boolean = false,
    )

    private data class PendingScreenCapture(
        val attempted: Boolean,
        val text: String?,
        val capturedAtMs: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val history: StateFlow<List<AssistantTurn>> = turns.asStateFlow()

    private val wakeWordLock = Any()

    @Volatile
    private var wakeWordListener: WakeWordListener? = null

    @Volatile
    private var wakeWordPausedForDictation = false

    /** Hasil tangkapan satu-kali sebelum Activity asisten menutupi aplikasi asal. */
    @Volatile
    private var pendingScreenCapture: PendingScreenCapture? = null

    /** Proses satu kalimat secara serial supaya konfirmasi pesan tidak bisa dieksekusi ganda. */
    suspend fun handleText(text: String, speakReply: Boolean? = null): AssistantTurn = commandMutex.withLock {
        withWakeWordPaused { handleTextLocked(text, speakReply) }
    }

    private suspend fun handleTextLocked(text: String, speakReply: Boolean?): AssistantTurn {
        val cleanText = text.trim()
        if (_state.value.thinking || _state.value.listening) {
            return AssistantTurn(cleanText, null, "Tunggu sampai sesi asisten selesai.", "busy")
        }
        val pending = _state.value.pendingMessage
        if (pending != null) {
            _state.value = _state.value.copy(currentCommand = cleanText, currentReply = "")
            return resolvePendingMessage(cleanText, pending, speakReply)
        }

        val settings = graph.settings.current()
        _state.value = _state.value.copy(
            thinking = true,
            status = "Memproses…",
            error = null,
            partial = "",
            currentCommand = cleanText,
            currentReply = "",
        )

        val screenContextRequested = ScreenContextRequest.isRequested(cleanText)
        val screenContext = try {
            readScreenContextIfAllowed(settings, screenContextRequested)
        } catch (t: Throwable) {
            VoiceLog.e("Gagal mengambil snapshot konteks layar", t)
            null
        }

        val understanding = try {
            graph.brain.understand(
                userText = cleanText,
                settings = settings,
                history = turns.value
                    .filterNot { it.source.contains(SCREEN_CONTEXT_SOURCE_TAG, ignoreCase = true) }
                    .takeLast(6)
                    .flatMap { listOf("user" to it.userText, "assistant" to it.reply) },
                screenContext = screenContext,
                screenContextRequested = screenContextRequested,
            )
        } catch (t: Throwable) {
            VoiceLog.e("Pemahaman gagal", t)
            val turn = AssistantTurn(cleanText, null, t.message ?: "Gagal memahami perintah", "error")
            return publishTurn(
                turn,
                settings,
                speakReply,
                persistHistory = screenContext == null,
                screenContextDerived = screenContext != null,
            )
        }

        var reply = understanding.reply
        var actionForTurn = understanding.action
        val pendingMessage = understanding.action as? AssistantAction.SendMessage
        if (pendingMessage != null) {
            if (pendingMessage.body.isBlank()) {
                // Jangan membuka chat dengan pesan kosong. Minta pengguna/LLM
                // memperjelas isi pesan sebelum aksi apa pun dijalankan.
                actionForTurn = null
                reply = "Isi pesan untuk ${pendingMessage.contactName} belum jelas. Sebutkan isi balasan terlebih dahulu."
            } else {
                _state.value = _state.value.copy(
                    pendingMessage = pendingMessage,
                    pendingMessageCreatedAtMs = System.currentTimeMillis(),
                    pendingMessageUsesScreenContext = screenContext != null,
                )
                reply = "Saya akan menyiapkan draf pesan untuk ${pendingMessage.contactName}: ${pendingMessage.body}. " +
                    "Apakah saya buka draf? Ucapkan ‘ya, siapkan draf’ atau ‘tidak, batal’. Pesan belum dikirim."
            }
        } else if (understanding.action != null) {
            val outcome = graph.assistantExecutor.execute(understanding.action, settings)
            reply = outcome.message.ifBlank { reply }
        }

        val turn = AssistantTurn(
            userText = cleanText,
            action = actionForTurn,
            reply = reply,
            source = if (screenContext != null) {
                "${understanding.source} · $SCREEN_CONTEXT_SOURCE_TAG"
            } else {
                understanding.source
            },
        )
        return publishTurn(
            turn,
            settings,
            speakReply,
            persistHistory = screenContext == null,
            screenContextDerived = screenContext != null,
        )
    }

    /** Konfirmasi lewat tombol UI; pesan tetap dibuka sebagai draft di aplikasi tujuan. */
    suspend fun confirmPendingMessage(): AssistantTurn? = commandMutex.withLock {
        withWakeWordPaused {
            if (_state.value.listening || _state.value.thinking) return@withWakeWordPaused null
            val pending = _state.value.pendingMessage ?: return@withWakeWordPaused null
            if (isPendingMessageExpired()) {
                return@withWakeWordPaused expirePendingMessage("Konfirmasi dari pengguna", speakReply = null)
            }
            executePendingMessage("Konfirmasi dari pengguna", pending, speakReply = null)
        }
    }

    private fun isPendingMessageExpired(): Boolean {
        val createdAt = _state.value.pendingMessageCreatedAtMs ?: return true
        return System.currentTimeMillis() - createdAt > PENDING_MESSAGE_TTL_MS
    }

    private suspend fun expirePendingMessage(text: String, speakReply: Boolean?): AssistantTurn {
        val expired = _state.value.pendingMessage
        val screenContextDerived = _state.value.pendingMessageUsesScreenContext
        _state.value = _state.value.copy(
            pendingMessage = null,
            pendingMessageCreatedAtMs = null,
            pendingMessageUsesScreenContext = false,
            thinking = true,
            status = "Konfirmasi kedaluwarsa",
        )
        val settings = graph.settings.current()
        return publishTurn(
            AssistantTurn(
                userText = text,
                action = null,
                reply = "Konfirmasi pesan untuk ${expired?.contactName ?: "penerima"} kedaluwarsa. Tidak ada pesan yang disiapkan.",
                source = "konfirmasi kedaluwarsa",
            ),
            settings,
            speakReply,
            pendingMessage = null,
            persistHistory = !screenContextDerived,
            screenContextDerived = screenContextDerived,
        )
    }

    /** Batalkan pesan yang sedang menunggu persetujuan. */
    suspend fun cancelPendingMessage(): AssistantTurn? = commandMutex.withLock {
        withWakeWordPaused {
            if (_state.value.listening || _state.value.thinking) return@withWakeWordPaused null
            val pending = _state.value.pendingMessage ?: return@withWakeWordPaused null
            val screenContextDerived = _state.value.pendingMessageUsesScreenContext
            _state.value = _state.value.copy(
                pendingMessage = null,
                pendingMessageCreatedAtMs = null,
                pendingMessageUsesScreenContext = false,
                thinking = true,
                status = "Membatalkan pesan…",
            )
            val settings = graph.settings.current()
            val turn = AssistantTurn(
                userText = "Batal",
                action = null,
                reply = "Baik, pesan untuk ${pending.contactName} dibatalkan dan tidak disiapkan.",
                source = "konfirmasi pengguna",
            )
            publishTurn(
                turn,
                settings,
                pendingMessage = null,
                persistHistory = !screenContextDerived,
                screenContextDerived = screenContextDerived,
            )
        }
    }

    private suspend fun resolvePendingMessage(
        text: String,
        pending: AssistantAction.SendMessage,
        speakReply: Boolean?,
    ): AssistantTurn {
        if (isPendingMessageExpired()) return expirePendingMessage(text, speakReply)
        val screenContextDerived = _state.value.pendingMessageUsesScreenContext
        return when (ConfirmationParser.parse(text)) {
            ConfirmationParser.Decision.YES -> executePendingMessage(text, pending, speakReply)
            ConfirmationParser.Decision.NO -> {
                _state.value = _state.value.copy(
                    pendingMessage = null,
                    pendingMessageCreatedAtMs = null,
                    pendingMessageUsesScreenContext = false,
                    thinking = true,
                    status = "Membatalkan pesan…",
                )
                val settings = graph.settings.current()
                publishTurn(
                    AssistantTurn(
                        userText = text,
                        action = null,
                        reply = "Baik, pesan untuk ${pending.contactName} dibatalkan dan tidak disiapkan.",
                        source = "konfirmasi pengguna",
                    ),
                    settings,
                    speakReply,
                    pendingMessage = null,
                    persistHistory = !screenContextDerived,
                    screenContextDerived = screenContextDerived,
                )
            }
            ConfirmationParser.Decision.UNCLEAR -> {
                val settings = graph.settings.current()
                publishTurn(
                    AssistantTurn(
                        userText = text,
                        action = null,
                        reply = "Saya masih menunggu konfirmasi. Ucapkan ‘ya, siapkan draf’ untuk membukanya, atau ‘tidak, batal’.",
                        source = "konfirmasi pengguna",
                    ),
                    settings,
                    speakReply,
                    pendingMessage = pending,
                    persistHistory = !screenContextDerived,
                    screenContextDerived = screenContextDerived,
                )
            }
        }
    }

    private suspend fun executePendingMessage(
        confirmationText: String,
        action: AssistantAction.SendMessage,
        speakReply: Boolean?,
    ): AssistantTurn {
        // Hapus pending sebelum menjalankan Intent agar ketukan/giliran kedua tidak
        // dapat membuka pesan ganda. Pengiriman akhir tetap memerlukan tap di WhatsApp.
        val screenContextDerived = _state.value.pendingMessageUsesScreenContext
        _state.value = _state.value.copy(
            pendingMessage = null,
            pendingMessageCreatedAtMs = null,
            pendingMessageUsesScreenContext = false,
            thinking = true,
            status = "Menyiapkan pesan…",
            error = null,
        )
        val settings = graph.settings.current()
        val outcome = graph.assistantExecutor.execute(action, settings)
        val turn = AssistantTurn(
            userText = confirmationText,
            action = action,
            reply = outcome.message.ifBlank {
                if (outcome.success) "Pesan disiapkan." else "Pesan tidak dapat disiapkan."
            },
            source = "konfirmasi pengguna",
        )
        return publishTurn(
            turn,
            settings,
            speakReply,
            pendingMessage = null,
            persistHistory = !screenContextDerived,
            screenContextDerived = screenContextDerived,
        )
    }

    private suspend fun publishTurn(
        turn: AssistantTurn,
        settings: AppSettings,
        speakReply: Boolean? = null,
        pendingMessage: AssistantAction.SendMessage? = _state.value.pendingMessage,
        persistHistory: Boolean = true,
        screenContextDerived: Boolean = false,
    ): AssistantTurn {
        val publishedTurn = if (screenContextDerived && !turn.source.contains(SCREEN_CONTEXT_SOURCE_TAG, ignoreCase = true)) {
            turn.copy(source = "${turn.source} · $SCREEN_CONTEXT_SOURCE_TAG")
        } else {
            turn
        }
        val pendingCreatedAt = if (pendingMessage == null) null else _state.value.pendingMessageCreatedAtMs
        val pendingUsesScreenContext = pendingMessage != null && _state.value.pendingMessageUsesScreenContext
        _state.value = _state.value.copy(
            thinking = true,
            status = publishedTurn.reply,
            currentCommand = publishedTurn.userText,
            currentReply = publishedTurn.reply,
            pendingMessage = pendingMessage,
            pendingMessageCreatedAtMs = pendingCreatedAt,
            pendingMessageUsesScreenContext = pendingUsesScreenContext,
        )
        turns.value = (turns.value + publishedTurn).takeLast(50)
        if (persistHistory && !screenContextDerived) graph.history.addAssistant(publishedTurn, settings.saveHistory)

        val shouldSpeak = speakReply ?: settings.speakReplies
        if (shouldSpeak) {
            runCatching {
                graph.tts.speakAndWait(
                    publishedTurn.reply,
                    settings.assistantLanguage,
                    voiceName = settings.ttsVoiceName,
                    pitch = settings.ttsPitch,
                    speechRate = settings.ttsSpeechRate,
                )
            }.onFailure { VoiceLog.w("TTS jawaban gagal: ${it.message}") }
        }

        _state.value = _state.value.copy(
            thinking = false,
            status = publishedTurn.reply,
            currentCommand = publishedTurn.userText,
            currentReply = publishedTurn.reply,
            lastTurn = publishedTurn,
            error = null,
            pendingMessage = pendingMessage,
            pendingMessageCreatedAtMs = pendingCreatedAt,
            pendingMessageUsesScreenContext = pendingUsesScreenContext,
        )
        return publishedTurn
    }

    private suspend fun <T> withWakeWordPaused(block: suspend () -> T): T {
        val shouldResumeWakeWord = !wakeWordPausedForDictation
        if (shouldResumeWakeWord) pauseWakeWordForDictation()
        return try {
            block()
        } finally {
            if (shouldResumeWakeWord) resumeWakeWordAfterDictation()
        }
    }

    private suspend fun readScreenContextIfAllowed(
        settings: AppSettings,
        explicitlyRequested: Boolean,
    ): String? {
        val savedCapture = pendingScreenCapture.also { pendingScreenCapture = null }
        if (!settings.screenContextEnabled || !explicitlyRequested) return null
        if (savedCapture?.attempted == true) {
            if (System.currentTimeMillis() - savedCapture.capturedAtMs > SCREEN_CAPTURE_TTL_MS) return null
            return savedCapture.text
        }
        return TextInsertAccessibilityService.readActiveScreenContext()
    }

    private suspend fun retryAssistantTextWithHybrid(settings: AppSettings, cause: Throwable): Result<String> {
        if (settings.sttEngine != SttEngineChoice.AUTO) return Result.failure(cause)
        VoiceLog.w("STT live asisten gagal; meminta ulangan lalu mencoba kaskade STT: ${cause.message}")
        _state.value = _state.value.copy(
            partial = "",
            currentCommand = "",
            currentReply = "",
            status = "Silakan ulangi perintah…",
        )
        if (settings.speakReplies) {
            runCatching {
                graph.tts.speakAndWait(
                    "Silakan ulangi.",
                    settings.assistantLanguage,
                    voiceName = settings.ttsVoiceName,
                    pitch = settings.ttsPitch,
                    speechRate = settings.ttsSpeechRate,
                )
            }.onFailure { VoiceLog.w("Prompt ulangan asisten gagal: ${it.message}") }
        }
        return graph.dictation.listenForText(
            settings = settings,
            silenceTimeoutMs = assistantSilenceTimeoutMs(settings),
            onPartial = ::updateAssistantPartial,
        )
    }

    private fun updateAssistantPartial(partial: String) {
        val command = partial.trim()
        if (command.isBlank()) return
        _state.value = _state.value.copy(partial = command, currentCommand = command)
    }

    /** Voice command selesai setelah jeda yang dipilih, maksimal 3 detik. */
    private fun assistantSilenceTimeoutMs(settings: AppSettings): Long =
        settings.silenceTimeoutMs.toLong().coerceIn(MIN_ASSISTANT_SILENCE_TIMEOUT_MS, ASSISTANT_SILENCE_TIMEOUT_MS)

    /**
     * Voice command memakai recognizer live bila tersedia, agar ucapan diproses
     * sambil pengguna bicara. Mode AUTO hanya memakai jalur ini jika recognizer
     * on-device tersedia; selain itu tetap gunakan kaskade STT yang dipilih.
     */
    private suspend fun listenForAssistantText(settings: AppSettings): Result<String> {
        val liveAvailable = if (settings.sttEngine == SttEngineChoice.SYSTEM_LIVE ||
            settings.sttEngine == SttEngineChoice.AUTO
        ) graph.stt.systemAvailable() else false
        val useLive = settings.sttEngine == SttEngineChoice.SYSTEM_LIVE ||
            (settings.sttEngine == SttEngineChoice.AUTO && liveAvailable && graph.stt.onDeviceAvailable())
        if (!useLive) {
            return graph.dictation.listenForText(
                settings = settings,
                silenceTimeoutMs = assistantSilenceTimeoutMs(settings),
                onPartial = ::updateAssistantPartial,
            )
        }
        if (!liveAvailable) {
            return Result.failure(IllegalStateException("Recognizer sistem tidak tersedia"))
        }

        return try {
            val transcript = graph.stt.listenLive(
                settings = settings,
                onPartial = ::updateAssistantPartial,
                onLevel = {},
                timeoutMs = ASSISTANT_STT_TIMEOUT_MS,
                silenceTimeoutMs = assistantSilenceTimeoutMs(settings),
            )
            if (transcript.text.isBlank()) {
                retryAssistantTextWithHybrid(settings, IllegalStateException("Tidak ada ucapan yang terdengar"))
            } else {
                Result.success(transcript.text.trim())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            retryAssistantTextWithHybrid(settings, t)
        }
    }

    /** Dengarkan sekali, lalu proses (wake word, bubble assistant, atau tombol Bicara). */
    suspend fun listenAndHandle(
        speakReply: Boolean? = null,
        announceReady: Boolean = false,
    ) {
        if (_state.value.listening || _state.value.thinking) return
        if (!graph.dictation.hasMicrophonePermission()) {
            _state.value = _state.value.copy(
                listening = false,
                thinking = false,
                needsMicrophonePermission = true,
                status = "",
                error = "Izin mikrofon belum diberikan",
            )
            return
        }

        var wakeWordPaused = false
        try {
            _state.value = _state.value.copy(
                listening = false,
                thinking = true,
                partial = "",
                currentCommand = "",
                currentReply = "",
                status = "Menyiapkan mikrofon…",
                error = null,
                needsMicrophonePermission = false,
            )
            pauseWakeWordForDictation()
            wakeWordPaused = true
            val settings = graph.settings.current()

            if (announceReady && settings.speakReplies) {
                // TTS harus selesai dulu: jika STT dimulai bersamaan, prompt siap
                // bisa masuk sebagai transkrip atau membuat SpeechRecognizer sibuk.
                val spoken = runCatching {
                    graph.tts.speakAndWait(
                        "Siap.",
                        settings.assistantLanguage,
                        voiceName = settings.ttsVoiceName,
                        pitch = settings.ttsPitch,
                        speechRate = settings.ttsSpeechRate,
                    )
                }.getOrElse {
                    VoiceLog.w("Prompt siap asisten gagal: ${it.message}")
                    false
                }
                if (!spoken) VoiceLog.w("Prompt siap asisten tidak selesai; melanjutkan sesi dengar")
            }

            _state.value = _state.value.copy(
                listening = true,
                thinking = false,
                partial = "",
                status = "Mendengarkan…",
                error = null,
            )
            val result = listenForAssistantText(settings)
            result.onSuccess { text ->
                val recognizedCommand = text.trim()
                if (recognizedCommand.isNotBlank()) {
                    // Publikasikan hasil final sebelum parse/aksi berjalan, agar popup
                    // menampilkan perintah meski mesin STT tidak mengirim partial.
                    _state.value = _state.value.copy(
                        listening = false,
                        partial = recognizedCommand,
                        currentCommand = recognizedCommand,
                        currentReply = "",
                        status = "Memproses…",
                        error = null,
                    )
                    handleText(recognizedCommand, speakReply)
                } else {
                    pendingScreenCapture = null
                    _state.value = _state.value.copy(
                        listening = false,
                        thinking = false,
                        status = "",
                        error = "Tidak mendengar apa pun",
                    )
                }
            }.onFailure { error ->
                pendingScreenCapture = null
                val needsPermission = !graph.dictation.hasMicrophonePermission()
                _state.value = _state.value.copy(
                    listening = false,
                    thinking = false,
                    needsMicrophonePermission = needsPermission,
                    status = "",
                    error = error.message ?: "Tidak mendengar apa pun",
                )
            }
        } catch (t: Throwable) {
            pendingScreenCapture = null
            VoiceLog.e("Sesi asisten gagal", t)
            _state.value = _state.value.copy(listening = false, thinking = false, error = t.message)
        } finally {
            if (wakeWordPaused) resumeWakeWordAfterDictation()
        }
    }

    fun startWakeWordIfEnabled() {
        scope.launch {
            val settings = graph.settings.current()
            if (!settings.wakeWordEnabled) {
                stopWakeWord()
                return@launch
            }
            synchronized(wakeWordLock) {
                if (wakeWordPausedForDictation || wakeWordListener != null) return@synchronized
                val listener = WakeWordListener(
                    context = context,
                    phrase = settings.wakeWordPhrase,
                    onWake = { heard ->
                        // Hentikan loop KWS sebelum memulai recognizer perintah kedua.
                        // Ini mencegah sesi wake word berikutnya berebut SpeechRecognizer.
                        stopWakeWord()
                        VoiceLog.i("Wake word membuka sesi asisten: $heard")
                        _state.value = _state.value.copy(status = "Kata kunci terdeteksi: \"$heard\"")
                        // Sama seperti tahan Volume Atas: snapshot layar sebelum popup,
                        // lalu langsung buka overlay asisten dan dengarkan perintah.
                        openAssistantUi(autoListen = true, captureScreenContext = true)
                    },
                    onStateChange = { status -> _state.value = _state.value.copy(status = status) },
                    // Mengalah terhadap dikte dan sesi asisten yang sedang berjalan:
                    // dua sesi recognizer sekaligus saling ditolak (ERROR_RECOGNIZER_BUSY).
                    isYielded = {
                        graph.dictation.state.value.isBusy || _state.value.listening || _state.value.thinking
                    },
                )
                wakeWordListener = listener
                listener.start(scope, settings.assistantLanguage, preferOnDevice = true)
            }
        }
    }

    /** Hentikan wake-word dan tunggu sampai sesi recognizer terlepas. */
    suspend fun pauseWakeWordForDictation() {
        wakeWordPausedForDictation = true
        val listener = synchronized(wakeWordLock) {
            wakeWordListener.also { wakeWordListener = null }
        }
        listener?.stopAndJoin()
        // Tunggu juga bila sesi SpeechRecognizer lain di aplikasi masih sedang
        // membersihkan diri (mis. penghentian asisten bertepatan dengan dikte).
        RecognizerGate.withSession { }
    }

    /** Sambung kembali wake-word setelah dikte/asisten selesai, bila masih aktif di Pengaturan. */
    fun resumeWakeWordAfterDictation() {
        wakeWordPausedForDictation = false
        startWakeWordIfEnabled()
    }

    fun stopWakeWord() {
        val listener = synchronized(wakeWordLock) {
            wakeWordListener.also { wakeWordListener = null }
        }
        listener?.stop()
    }

    /**
     * Buka asisten dari luar (bubble, tile, wake word, tombol volume, atau sistem).
     * Jika izin overlay dan mikrofon siap, pertahankan aplikasi asal tetap di depan
     * dan tampilkan sesi lewat popup; jika tidak, gunakan layar asisten sebagai fallback.
     */
    fun openAssistantUi(autoListen: Boolean, captureScreenContext: Boolean = false) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_ASSISTANT, true)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, autoListen)
        }

        val canListenOverCurrentApp = autoListen &&
            OverlayBubbleService.hasPermission(context) && graph.dictation.hasMicrophonePermission()
        if (canListenOverCurrentApp) {
            scope.launch {
                // Capture konteks layar opt-in sebelum overlay muncul; lewati prompt
                // suara agar recognizer segera siap menerima perintah.
                capturePendingScreenContext(captureScreenContext)
                listenAndHandle(announceReady = false)
            }
            return
        }

        if (!captureScreenContext || !autoListen) {
            withContextNoThrow { context.startActivity(intent) }
            return
        }

        scope.launch {
            capturePendingScreenContext(enabled = true)
            withContext(Dispatchers.Main.immediate) {
                withContextNoThrow { context.startActivity(intent) }
            }
        }
    }

    private suspend fun capturePendingScreenContext(enabled: Boolean) {
        if (!enabled) {
            pendingScreenCapture = null
            return
        }
        val settings = runCatching { graph.settings.current() }.getOrNull()
        val captured = if (settings?.screenContextEnabled == true) {
            runCatching { TextInsertAccessibilityService.readActiveScreenContext() }
                .onFailure { VoiceLog.e("Capture konteks layar sebelum sesi asisten gagal", it) }
                .getOrNull()
        } else {
            null
        }
        pendingScreenCapture = PendingScreenCapture(attempted = true, text = captured)
    }

    private fun withContextNoThrow(block: () -> Unit) {
        runCatching { block() }.onFailure { VoiceLog.e("Gagal membuka UI asisten", it) }
    }

    fun clearHistory() {
        turns.value = emptyList()
        pendingScreenCapture = null
        _state.value = _state.value.copy(
            pendingMessage = null,
            pendingMessageCreatedAtMs = null,
            pendingMessageUsesScreenContext = false,
        )
    }

    fun setError(message: String?) {
        _state.value = _state.value.copy(error = message)
    }

    fun currentSettings(): AppSettings = kotlinx.coroutines.runBlocking { graph.settings.current() }

    fun shutdown() {
        stopWakeWord()
        graph.tts.shutdown()
    }

    private companion object {
        const val SCREEN_CAPTURE_TTL_MS = 30_000L
        const val PENDING_MESSAGE_TTL_MS = 90_000L
        const val ASSISTANT_STT_TIMEOUT_MS = 20_000L
        const val MIN_ASSISTANT_SILENCE_TIMEOUT_MS = 400L
        const val ASSISTANT_SILENCE_TIMEOUT_MS = 3_000L
        const val SCREEN_CONTEXT_SOURCE_TAG = "konteks layar"
    }
}
