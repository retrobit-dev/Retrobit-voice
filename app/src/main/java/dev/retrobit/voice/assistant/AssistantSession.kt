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
        /** Panggilan keluar/menjawab masuk yang belum dijalankan sebelum persetujuan eksplisit. */
        val pendingPhoneAction: AssistantAction? = null,
        val pendingPhoneActionPrompt: String = "",
        val pendingPhoneActionCreatedAtMs: Long? = null,
        val pendingPhoneActionUsesScreenContext: Boolean = false,
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

    private var wakeWordStartGeneration = 0L

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
        val waitingMessage = _state.value.pendingMessage
        val waitingPhoneAction = _state.value.pendingPhoneAction
        when (UniversalCommandParser.parse(cleanText)) {
            UniversalCommandParser.Command.CANCEL_PENDING -> {
                pendingScreenCapture = null
                when {
                    waitingMessage != null -> {
                        _state.value = _state.value.copy(currentCommand = cleanText, currentReply = "")
                        return resolvePendingMessage(cleanText, waitingMessage, speakReply)
                    }
                    waitingPhoneAction != null -> {
                        _state.value = _state.value.copy(currentCommand = cleanText, currentReply = "")
                        return resolvePendingPhoneAction(cleanText, waitingPhoneAction, speakReply)
                    }
                    else -> {
                        val settings = graph.settings.current()
                        val english = settings.assistantLanguage.startsWith("en", ignoreCase = true)
                        _state.value = _state.value.copy(
                            thinking = true,
                            status = "Memproses…",
                            error = null,
                            partial = "",
                            currentCommand = cleanText,
                            currentReply = "",
                        )
                        return publishTurn(
                            AssistantTurn(
                                cleanText,
                                null,
                                if (english) "There is no pending action to cancel." else "Tidak ada aksi yang menunggu konfirmasi.",
                                "perintah universal",
                            ),
                            settings,
                            speakReply,
                        )
                    }
                }
            }
            UniversalCommandParser.Command.REPEAT_LAST_REPLY -> {
                pendingScreenCapture = null
                if (waitingMessage != null && isPendingMessageExpired()) {
                    return expirePendingMessage(cleanText, speakReply)
                }
                if (waitingPhoneAction != null && isPendingPhoneActionExpired()) {
                    return expirePendingPhoneAction(cleanText, speakReply)
                }
                return repeatLastReply(cleanText, speakReply)
            }
            null -> Unit
        }
        if (waitingMessage != null) {
            _state.value = _state.value.copy(currentCommand = cleanText, currentReply = "")
            return resolvePendingMessage(cleanText, waitingMessage, speakReply)
        }
        if (waitingPhoneAction != null) {
            _state.value = _state.value.copy(currentCommand = cleanText, currentReply = "")
            return resolvePendingPhoneAction(cleanText, waitingPhoneAction, speakReply)
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

        val capabilityQuestion = CapabilityQuestionParser.parse(cleanText)
        val whatsappCapabilityQuestion = capabilityQuestion?.topic?.let { topic ->
            topic == CapabilityQuestionParser.Topic.WHATSAPP_OVERVIEW ||
                topic == CapabilityQuestionParser.Topic.WHATSAPP_CONTACTS ||
                topic == CapabilityQuestionParser.Topic.WHATSAPP_CHAT ||
                topic == CapabilityQuestionParser.Topic.WHATSAPP_CALL
        } == true
        val screenContextRequested = !whatsappCapabilityQuestion && ScreenContextRequest.isRequested(cleanText)
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
        var actionReplyWasAnnounced = false
        val pendingPhoneAction = understanding.action?.takeIf { PhoneActionConfirmation.isRequired(it) }
        val pendingMessage = understanding.action as? AssistantAction.SendMessage
        if (pendingPhoneAction != null) {
            val english = settings.assistantLanguage.startsWith("en", ignoreCase = true)
            val prompt = PhoneActionConfirmation.prompt(pendingPhoneAction, english)
            _state.value = _state.value.copy(
                pendingPhoneAction = pendingPhoneAction,
                pendingPhoneActionPrompt = prompt,
                pendingPhoneActionCreatedAtMs = System.currentTimeMillis(),
                pendingPhoneActionUsesScreenContext = screenContext != null,
            )
            reply = prompt
        } else if (pendingMessage != null) {
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
            if (understanding.action == AssistantAction.StartDictation) {
                val hasTextTarget = graph.textInserter.imeReady() || TextInsertAccessibilityService.hasFocusedTextField()
                val canStart = graph.dictation.hasMicrophonePermission() &&
                    hasTextTarget &&
                    !graph.dictation.state.value.isBusy &&
                    !graph.speakerVerification.state.value.enrollmentActive
                if (!canStart) {
                    actionForTurn = null
                    val english = settings.assistantLanguage.startsWith("en", ignoreCase = true)
                    reply = when {
                        !graph.dictation.hasMicrophonePermission() -> if (english) {
                            "Microphone permission is required to start dictation."
                        } else {
                            "Izin mikrofon diperlukan untuk memulai dikte."
                        }
                        !hasTextTarget -> if (english) {
                            "Focus a text field first. Dictation will be inserted at its active cursor."
                        } else {
                            "Fokuskan kolom teks terlebih dahulu. Hasil dikte akan masuk di posisi kursor aktif."
                        }
                        graph.speakerVerification.state.value.enrollmentActive -> if (english) {
                            "Voice enrollment is in progress. Finish or cancel it before starting dictation."
                        } else {
                            "Enrolmen suara sedang berlangsung. Selesaikan atau batalkan sebelum memulai dikte."
                        }
                        else -> if (english) {
                            "Dictation is already running. Finish or cancel it before starting another session."
                        } else {
                            "Dikte sedang berjalan. Selesaikan atau batalkan dulu sebelum memulai lagi."
                        }
                    }
                } else {
                    val shouldSpeak = speakReply ?: settings.speakReplies
                    val announced = !shouldSpeak || runCatching {
                        graph.tts.speakAndWait(
                            if (settings.assistantLanguage.startsWith("en", ignoreCase = true)) {
                                "Dictation will start now. Please speak after this prompt finishes."
                            } else {
                                "Dikte akan dimulai. Silakan bicara setelah instruksi ini selesai."
                            },
                            settings.assistantLanguage,
                            voiceName = settings.ttsVoiceName,
                            pitch = settings.ttsPitch,
                            speechRate = settings.ttsSpeechRate,
                        )
                    }.getOrDefault(false)
                    if (!announced) {
                        actionForTurn = null
                        reply = if (settings.assistantLanguage.startsWith("en", ignoreCase = true)) {
                            "Dictation was not started because voice guidance is unavailable. Use the Dictation button."
                        } else {
                            "Dikte tidak dimulai karena panduan suara tidak tersedia. Gunakan tombol Dikte."
                        }
                    } else {
                        val outcome = graph.assistantExecutor.execute(understanding.action, settings)
                        reply = outcome.message.ifBlank { reply }
                        actionReplyWasAnnounced = shouldSpeak && outcome.success
                    }
                }
            } else {
                val outcome = graph.assistantExecutor.execute(understanding.action, settings)
                reply = outcome.message.ifBlank { reply }
            }
        }

        val turnUserText = if (understanding.action is AssistantAction.TypeText) {
            if (settings.assistantLanguage.startsWith("en", ignoreCase = true)) {
                "Type [text hidden]"
            } else {
                "Ketik [teks disembunyikan]"
            }
        } else {
            cleanText
        }
        val turn = AssistantTurn(
            userText = turnUserText,
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
            speakReply = if (actionReplyWasAnnounced) false else speakReply,
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

    /** Konfirmasi aksi panggilan lewat tombol UI; jalur suara memakai jawaban ya/tidak. */
    suspend fun confirmPendingPhoneAction(): AssistantTurn? = commandMutex.withLock {
        withWakeWordPaused {
            if (_state.value.listening || _state.value.thinking) return@withWakeWordPaused null
            val pending = _state.value.pendingPhoneAction ?: return@withWakeWordPaused null
            if (isPendingPhoneActionExpired()) {
                return@withWakeWordPaused expirePendingPhoneAction("Konfirmasi dari pengguna", speakReply = null)
            }
            executePendingPhoneAction("Konfirmasi dari pengguna", pending, speakReply = null)
        }
    }

    /** Batalkan panggilan yang sedang menunggu persetujuan tanpa memanggil executor. */
    suspend fun cancelPendingPhoneAction(): AssistantTurn? = commandMutex.withLock {
        withWakeWordPaused {
            if (_state.value.listening || _state.value.thinking) return@withWakeWordPaused null
            val pending = _state.value.pendingPhoneAction ?: return@withWakeWordPaused null
            cancelPendingPhoneAction("Batal", pending, speakReply = null)
        }
    }

    private fun isPendingPhoneActionExpired(): Boolean {
        val createdAt = _state.value.pendingPhoneActionCreatedAtMs ?: return true
        return System.currentTimeMillis() - createdAt > PENDING_PHONE_ACTION_TTL_MS
    }

    private suspend fun resolvePendingPhoneAction(
        text: String,
        pending: AssistantAction,
        speakReply: Boolean?,
    ): AssistantTurn {
        if (isPendingPhoneActionExpired()) return expirePendingPhoneAction(text, speakReply)
        val screenContextDerived = _state.value.pendingPhoneActionUsesScreenContext
        return when (ConfirmationParser.parse(text)) {
            ConfirmationParser.Decision.YES -> executePendingPhoneAction(text, pending, speakReply)
            ConfirmationParser.Decision.NO -> cancelPendingPhoneAction(text, pending, speakReply)
            ConfirmationParser.Decision.UNCLEAR -> {
                val settings = graph.settings.current()
                val english = settings.assistantLanguage.startsWith("en", ignoreCase = true)
                val prompt = PhoneActionConfirmation.prompt(pending, english)
                val reply = if (english) {
                    "I still need a clear yes or no. $prompt"
                } else {
                    "Saya masih menunggu jawaban ‘ya’ atau ‘batal’. $prompt"
                }
                publishTurn(
                    AssistantTurn(text, null, reply, "konfirmasi pengguna"),
                    settings,
                    speakReply,
                    persistHistory = !screenContextDerived,
                    screenContextDerived = screenContextDerived,
                )
            }
        }
    }

    private suspend fun cancelPendingPhoneAction(
        text: String,
        action: AssistantAction,
        speakReply: Boolean?,
    ): AssistantTurn {
        val screenContextDerived = _state.value.pendingPhoneActionUsesScreenContext
        _state.value = _state.value.copy(
            pendingPhoneAction = null,
            pendingPhoneActionPrompt = "",
            pendingPhoneActionCreatedAtMs = null,
            pendingPhoneActionUsesScreenContext = false,
            thinking = true,
            status = "Membatalkan panggilan…",
            error = null,
        )
        val settings = graph.settings.current()
        return publishTurn(
            AssistantTurn(
                text,
                null,
                PhoneActionConfirmation.cancellationReply(
                    action,
                    settings.assistantLanguage.startsWith("en", ignoreCase = true),
                ),
                "konfirmasi pengguna",
            ),
            settings,
            speakReply,
            persistHistory = !screenContextDerived,
            screenContextDerived = screenContextDerived,
        )
    }

    private suspend fun expirePendingPhoneAction(text: String, speakReply: Boolean?): AssistantTurn {
        val expired = _state.value.pendingPhoneAction ?: return AssistantTurn(
            text,
            null,
            "Konfirmasi sudah kedaluwarsa.",
            "konfirmasi kedaluwarsa",
        )
        val screenContextDerived = _state.value.pendingPhoneActionUsesScreenContext
        _state.value = _state.value.copy(
            pendingPhoneAction = null,
            pendingPhoneActionPrompt = "",
            pendingPhoneActionCreatedAtMs = null,
            pendingPhoneActionUsesScreenContext = false,
            thinking = true,
            status = "Konfirmasi kedaluwarsa",
            error = null,
        )
        val settings = graph.settings.current()
        return publishTurn(
            AssistantTurn(
                text,
                null,
                PhoneActionConfirmation.expiredReply(
                    expired,
                    settings.assistantLanguage.startsWith("en", ignoreCase = true),
                ),
                "konfirmasi kedaluwarsa",
            ),
            settings,
            speakReply,
            persistHistory = !screenContextDerived,
            screenContextDerived = screenContextDerived,
        )
    }

    private suspend fun executePendingPhoneAction(
        confirmationText: String,
        action: AssistantAction,
        speakReply: Boolean?,
    ): AssistantTurn {
        // Hapus pending sebelum membuka ACTION_CALL/acceptRingingCall agar konfirmasi ganda
        // tidak dapat memicu panggilan atau menjawab panggilan yang sama dua kali.
        val screenContextDerived = _state.value.pendingPhoneActionUsesScreenContext
        _state.value = _state.value.copy(
            pendingPhoneAction = null,
            pendingPhoneActionPrompt = "",
            pendingPhoneActionCreatedAtMs = null,
            pendingPhoneActionUsesScreenContext = false,
            thinking = true,
            status = "Menjalankan panggilan…",
            error = null,
        )
        val settings = graph.settings.current()
        val outcome = graph.assistantExecutor.execute(action, settings)
        val turn = AssistantTurn(
            confirmationText,
            action,
            outcome.message.ifBlank {
                if (outcome.success) "Aksi panggilan dijalankan." else "Aksi panggilan tidak dapat dijalankan."
            },
            "konfirmasi pengguna",
        )
        return publishTurn(
            turn,
            settings,
            speakReply,
            persistHistory = !screenContextDerived,
            screenContextDerived = screenContextDerived,
        )
    }

    private suspend fun repeatLastReply(text: String, speakReply: Boolean?): AssistantTurn {
        val lastTurn = turns.value.lastOrNull()
        val settings = graph.settings.current()
        val english = settings.assistantLanguage.startsWith("en", ignoreCase = true)
        val reply = lastTurn?.reply?.takeIf { it.isNotBlank() } ?: if (english) {
            "There is no previous answer to repeat."
        } else {
            "Belum ada jawaban yang bisa diulangi."
        }
        val screenContextDerived = lastTurn?.source?.contains(SCREEN_CONTEXT_SOURCE_TAG, ignoreCase = true) == true
        _state.value = _state.value.copy(
            thinking = true,
            status = "Mengulangi jawaban…",
            error = null,
            partial = "",
            currentCommand = text,
            currentReply = "",
        )
        // Jangan bawa action dari jawaban sebelumnya: mengulangi hanya membaca teks,
        // tidak pernah menjalankan ulang panggilan atau aksi lain yang berdampak.
        return publishTurn(
            AssistantTurn(text, null, reply, "perintah universal: ulangi"),
            settings,
            speakReply = speakReply ?: true,
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
        val pendingPhoneAction = _state.value.pendingPhoneAction
        val pendingPhoneActionPrompt = if (pendingPhoneAction == null) "" else _state.value.pendingPhoneActionPrompt
        val pendingPhoneActionCreatedAt = if (pendingPhoneAction == null) null else _state.value.pendingPhoneActionCreatedAtMs
        val pendingPhoneActionUsesScreenContext = pendingPhoneAction != null && _state.value.pendingPhoneActionUsesScreenContext
        _state.value = _state.value.copy(
            thinking = true,
            status = publishedTurn.reply,
            currentCommand = publishedTurn.userText,
            currentReply = publishedTurn.reply,
            pendingMessage = pendingMessage,
            pendingMessageCreatedAtMs = pendingCreatedAt,
            pendingMessageUsesScreenContext = pendingUsesScreenContext,
            pendingPhoneAction = pendingPhoneAction,
            pendingPhoneActionPrompt = pendingPhoneActionPrompt,
            pendingPhoneActionCreatedAtMs = pendingPhoneActionCreatedAt,
            pendingPhoneActionUsesScreenContext = pendingPhoneActionUsesScreenContext,
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
            pendingPhoneAction = pendingPhoneAction,
            pendingPhoneActionPrompt = pendingPhoneActionPrompt,
            pendingPhoneActionCreatedAtMs = pendingPhoneActionCreatedAt,
            pendingPhoneActionUsesScreenContext = pendingPhoneActionUsesScreenContext,
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

    /** Voice command selesai setelah jeda hening yang dipilih untuk asisten. */
    private fun assistantSilenceTimeoutMs(settings: AppSettings): Long =
        settings.assistantSilenceTimeoutMs.toLong().coerceIn(MIN_ASSISTANT_SILENCE_TIMEOUT_MS, MAX_ASSISTANT_SILENCE_TIMEOUT_MS)

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
        if (graph.speakerVerification.state.value.enrollmentActive) {
            _state.value = _state.value.copy(
                listening = false,
                thinking = false,
                status = "",
                error = context.getString(R.string.assistant_speaker_enrollment_active),
            )
            return
        }
        if (graph.wakeWordEnrollment.state.value.enrollmentActive) {
            _state.value = _state.value.copy(
                listening = false,
                thinking = false,
                status = "",
                error = context.getString(R.string.assistant_wake_word_enrollment_active),
            )
            return
        }
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

            if (announceReady) {
                // TTS harus selesai dulu: jika STT dimulai bersamaan, prompt siap
                // bisa masuk sebagai transkrip atau membuat SpeechRecognizer sibuk.
                val spoken = runCatching {
                    graph.tts.speakAndWait(
                        context.getString(R.string.assistant_ready_phrase),
                        settings.assistantLanguage,
                        voiceName = settings.ttsVoiceName,
                        pitch = settings.ttsPitch,
                        speechRate = settings.ttsSpeechRate,
                    )
                }.getOrElse {
                    VoiceLog.w("Prompt siap asisten gagal: ${it.message}")
                    false
                }
                if (!spoken) {
                    VoiceLog.w("Prompt siap asisten tidak selesai; sesi mikrofon dibatalkan")
                    _state.value = _state.value.copy(
                        listening = false,
                        thinking = false,
                        status = "",
                        error = context.getString(R.string.assistant_ready_speech_unavailable),
                    )
                    return
                }
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
        val ticket = synchronized(wakeWordLock) {
            ++wakeWordStartGeneration
            wakeWordStartGeneration
        }
        scope.launch {
            val settings = graph.settings.current()
            if (!settings.wakeWordEnabled) return@launch
            if (graph.wakeWordEnrollment.state.value.enrollmentActive) return@launch
            val profileReady = runCatching { graph.wakeWordEnrollment.hasProfileForPhrase(settings.wakeWordPhrase) }
                .onFailure { VoiceLog.e("Profil wake word lokal gagal dibuka", it) }
                .getOrDefault(false)
            if (!profileReady) {
                _state.value = _state.value.copy(status = context.getString(R.string.wake_word_profile_missing))
                return@launch
            }
            val latestSettings = graph.settings.current()
            if (
                !latestSettings.wakeWordEnabled ||
                WakeWordAudioFeatures.normalizePhrase(latestSettings.wakeWordPhrase) !=
                WakeWordAudioFeatures.normalizePhrase(settings.wakeWordPhrase)
            ) return@launch
            synchronized(wakeWordLock) {
                if (
                    ticket != wakeWordStartGeneration || wakeWordPausedForDictation || wakeWordListener != null ||
                    graph.wakeWordEnrollment.state.value.enrollmentActive
                ) return@synchronized
                val listener = WakeWordListener(
                    context = context,
                    phrase = settings.wakeWordPhrase,
                    kws = graph.wakeWordKws,
                    onWake = { heard ->
                        // Local KWS releases AudioRecord before the existing assistant STT starts.
                        stopWakeWord()
                        VoiceLog.i("Wake word lokal membuka sesi asisten: $heard")
                        _state.value = _state.value.copy(status = context.getString(R.string.wake_word_detected))
                        // Wake word always opens the assistant overlay, never dictation because a field is focused.
                        openAssistantUi(autoListen = true, captureScreenContext = true)
                    },
                    onStateChange = { status -> _state.value = _state.value.copy(status = status) },
                    onTerminated = { terminated ->
                        synchronized(wakeWordLock) {
                            if (wakeWordListener === terminated) wakeWordListener = null
                        }
                    },
                    // Release the microphone while dictation, assistant STT, or enrollment is active.
                    isYielded = {
                        graph.dictation.state.value.isBusy || _state.value.listening || _state.value.thinking ||
                            graph.speakerVerification.state.value.enrollmentActive ||
                            graph.wakeWordEnrollment.state.value.enrollmentActive
                    },
                )
                wakeWordListener = listener
                listener.start(scope)
            }
        }
    }

    /** Stop passive AudioRecord without setting the dictation pause flag. */
    suspend fun stopWakeWordForEnrollment() {
        val listener = synchronized(wakeWordLock) {
            wakeWordStartGeneration += 1
            wakeWordListener.also { wakeWordListener = null }
        }
        listener?.stopAndJoin()
    }

    /** Hentikan wake-word dan tunggu sampai recognizer sesi dikte dilepas. */
    suspend fun pauseWakeWordForDictation() {
        val listener = synchronized(wakeWordLock) {
            wakeWordPausedForDictation = true
            wakeWordStartGeneration += 1
            wakeWordListener.also { wakeWordListener = null }
        }
        listener?.stopAndJoin()
        // Tunggu juga bila sesi SpeechRecognizer lain di aplikasi masih sedang
        // membersihkan diri (mis. penghentian asisten bertepatan dengan dikte).
        RecognizerGate.withSession { }
    }

    /** Sambung kembali wake-word setelah dikte/asisten selesai, bila masih aktif di Pengaturan. */
    fun resumeWakeWordAfterDictation() {
        synchronized(wakeWordLock) { wakeWordPausedForDictation = false }
        startWakeWordIfEnabled()
    }

    fun stopWakeWord() {
        val listener = synchronized(wakeWordLock) {
            wakeWordStartGeneration += 1
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
                // Capture konteks layar opt-in sebelum sesi dibuka; listenAndHandle
                // mengucapkan prompt kesiapan sebelum recognizer mulai mendengarkan.
                capturePendingScreenContext(captureScreenContext)
                listenAndHandle(announceReady = true)
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
            pendingPhoneAction = null,
            pendingPhoneActionPrompt = "",
            pendingPhoneActionCreatedAtMs = null,
            pendingPhoneActionUsesScreenContext = false,
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
        const val PENDING_PHONE_ACTION_TTL_MS = 90_000L
        const val ASSISTANT_STT_TIMEOUT_MS = 20_000L
        const val MIN_ASSISTANT_SILENCE_TIMEOUT_MS = 500L
        const val MAX_ASSISTANT_SILENCE_TIMEOUT_MS = 5_000L
        const val SCREEN_CONTEXT_SOURCE_TAG = "konteks layar"
    }
}
