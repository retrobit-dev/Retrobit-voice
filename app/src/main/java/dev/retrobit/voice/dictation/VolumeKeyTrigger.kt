package dev.retrobit.voice.dictation

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logika murni pemicu tombol volume. Sengaja tanpa API Android supaya bisa
 * diuji di JVM (lihat `VolumeTriggerLogicTest`).
 *
 * Ketukan singkat diteruskan sebagai satu langkah volume. Jika tombol ditahan
 * sampai [holdThresholdMs], aksi khusus dijalankan dan tombol tidak mengubah volume.
 */
class VolumeTriggerLogic(
    val holdThresholdMs: Long = DEFAULT_HOLD_THRESHOLD_MS,
) {

    enum class Action { NONE, START, FINISH, VOLUME_CLICK }

    /** [consume] = true berarti peristiwa ditangani oleh Retrobit. */
    data class Decision(val action: Action, val consume: Boolean)

    private var pressed = false
    private var pressedAtMs = 0L
    private var triggerFired = false

    val isPressed: Boolean get() = pressed

    fun onDown(atMs: Long): Decision {
        if (!pressed) {
            pressed = true
            pressedAtMs = atMs
            triggerFired = false
        }
        // Tahan sebentar dulu: kalau segera dilepas, volume akan disesuaikan saat ACTION_UP.
        return Decision(Action.NONE, consume = true)
    }

    /** Dipanggil timer setelah tombol tertahan selama ambang batas. */
    fun onHold(atMs: Long): Decision {
        if (!pressed || triggerFired) return Decision(Action.NONE, consume = pressed)
        val heldMs = (atMs - pressedAtMs).coerceAtLeast(0L)
        if (heldMs < holdThresholdMs) return Decision(Action.NONE, consume = true)
        triggerFired = true
        return Decision(Action.START, consume = true)
    }

    fun onUp(atMs: Long): Decision {
        if (!pressed) return Decision(Action.NONE, consume = false)
        val heldMs = (atMs - pressedAtMs).coerceAtLeast(0L)
        val wasTriggered = triggerFired
        pressed = false
        pressedAtMs = 0L
        triggerFired = false
        return when {
            wasTriggered -> Decision(Action.FINISH, consume = true)
            heldMs < holdThresholdMs -> Decision(Action.VOLUME_CLICK, consume = true)
            // Ambang tercapai, tetapi timer mungkin dibatalkan saat setelan berubah.
            // Jangan keliru menganggap tekan lama sebagai klik volume.
            else -> Decision(Action.NONE, consume = true)
        }
    }

    fun reset() {
        pressed = false
        pressedAtMs = 0L
        triggerFired = false
    }

    private companion object {
        const val DEFAULT_HOLD_THRESHOLD_MS = 700L
    }
}

/**
 * Pintasan tombol volume melalui layanan aksesibilitas:
 * - tahan Volume Atas = mulai sesi asisten/voice command;
 * - tahan Volume Bawah = mulai dikte, lepas untuk selesai;
 * - ketukan singkat pada tombol yang diaktifkan tetap mengubah volume satu langkah.
 *
 * Kenapa aksesibilitas? Android tidak menyediakan API publik lain untuk membaca
 * tombol volume tanpa menangani penekanannya; layanan yang sama juga sudah dipakai
 * untuk menyisipkan hasil dikte.
 */
object VolumeKeyTrigger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val assistantLogic = VolumeTriggerLogic()
    private val dictationLogic = VolumeTriggerLogic()

    @Volatile
    private var dictationEnabled = false

    @Volatile
    private var assistantEnabled = false

    private var observerStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var assistantContext: Context? = null

    @Volatile
    private var dictationContext: Context? = null

    private val assistantHoldRunnable = Runnable {
        if (assistantEnabled) {
            val decision = assistantLogic.onHold(SystemClock.uptimeMillis())
            if (decision.action == VolumeTriggerLogic.Action.START) {
                assistantContext?.let(::startAssistant)
            }
        }
    }

    private val dictationHoldRunnable = Runnable {
        if (dictationEnabled) {
            val decision = dictationLogic.onHold(SystemClock.uptimeMillis())
            if (decision.action == VolumeTriggerLogic.Action.START) {
                dictationContext?.let(::startDictation)
            }
        }
    }

    /** Dipanggil layanan aksesibilitas saat tersambung. Aman dipanggil berkali-kali. */
    fun attach() = startObserver()

    /**
     * Meneruskan peristiwa tombol volume. Mengembalikan true kalau Retrobit
     * menanganinya (termasuk menyesuaikan volume untuk ketukan singkat).
     */
    fun onKeyEvent(context: Context, event: KeyEvent?): Boolean {
        if (event == null) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (assistantEnabled || assistantLogic.isPressed) onAssistantKeyEvent(context, event) else false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (dictationEnabled || dictationLogic.isPressed) onDictationKeyEvent(context, event) else false
            }
            else -> false
        }
    }

    private fun onAssistantKeyEvent(context: Context, event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> {
            val wasPressed = assistantLogic.isPressed
            val decision = assistantLogic.onDown(SystemClock.uptimeMillis())
            if (!wasPressed) {
                assistantContext = context.applicationContext
                mainHandler.removeCallbacks(assistantHoldRunnable)
                mainHandler.postDelayed(assistantHoldRunnable, assistantLogic.holdThresholdMs)
            }
            decision.consume
        }

        KeyEvent.ACTION_UP -> {
            mainHandler.removeCallbacks(assistantHoldRunnable)
            val decision = assistantLogic.onUp(SystemClock.uptimeMillis())
            if (decision.action == VolumeTriggerLogic.Action.VOLUME_CLICK) {
                adjustSystemVolume(context, KeyEvent.KEYCODE_VOLUME_UP)
            }
            assistantContext = null
            decision.consume
        }

        else -> assistantLogic.isPressed
    }

    private fun onDictationKeyEvent(context: Context, event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> {
            val wasPressed = dictationLogic.isPressed
            val decision = dictationLogic.onDown(SystemClock.uptimeMillis())
            if (!wasPressed) {
                dictationContext = context.applicationContext
                mainHandler.removeCallbacks(dictationHoldRunnable)
                mainHandler.postDelayed(dictationHoldRunnable, dictationLogic.holdThresholdMs)
            }
            decision.consume
        }

        KeyEvent.ACTION_UP -> {
            mainHandler.removeCallbacks(dictationHoldRunnable)
            val decision = dictationLogic.onUp(SystemClock.uptimeMillis())
            when (decision.action) {
                VolumeTriggerLogic.Action.VOLUME_CLICK -> adjustSystemVolume(context, KeyEvent.KEYCODE_VOLUME_DOWN)
                VolumeTriggerLogic.Action.FINISH -> finishVolumeDictation()
                VolumeTriggerLogic.Action.NONE,
                VolumeTriggerLogic.Action.START,
                -> Unit
            }
            dictationContext = null
            decision.consume
        }

        else -> dictationLogic.isPressed
    }

    /** Ketukan biasa meniru satu langkah tombol fisik dan tetap menampilkan slider Android. */
    private fun adjustSystemVolume(context: Context, keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        runCatching {
            audioManager.adjustVolume(
                direction,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND,
            )
        }.onFailure { VoiceLog.w("Ketukan volume tidak bisa diteruskan ke AudioManager: ${it.message}") }
    }

    private fun startAssistant(context: Context) {
        val graph = runCatching { AppGraph.require }.getOrNull() ?: return
        // AssistantSession memilih popup bila mic + overlay siap, atau membuka
        // layar asisten sebagai jalur fallback bila salah satu izin belum ada.
        if (graph.dictation.state.value.isBusy || graph.assistant.state.value.listening || graph.assistant.state.value.thinking) {
            ToastHub.show(context, context.getString(R.string.toast_volume_assistant_busy))
            return
        }
        runCatching {
            graph.assistant.openAssistantUi(autoListen = true, captureScreenContext = true)
        }.onFailure { error ->
            VoiceLog.e("Tombol volume gagal membuka asisten", error)
            ToastHub.show(context, context.getString(R.string.toast_volume_start_failed), long = true)
        }
    }

    private fun startDictation(context: Context) {
        val graph = runCatching { AppGraph.require }.getOrNull() ?: return
        if (!graph.dictation.hasMicrophonePermission()) {
            ToastHub.show(context, context.getString(R.string.toast_volume_needs_mic), long = true)
            return
        }
        if (graph.dictation.state.value.isBusy || graph.assistant.state.value.listening || graph.assistant.state.value.thinking) {
            ToastHub.show(context, context.getString(R.string.toast_volume_assistant_busy))
            return
        }
        graph.dictation.reset()
        runCatching { DictationService.start(context, DictationController.Source.VOLUME) }
            .onFailure { t ->
                // Biasanya pembatasan Android 12+ soal memulai service dari latar
                // belakang; beri tahu pengguna alih-alih gagal diam-diam.
                VoiceLog.e("Tombol volume gagal memulai dikte", t)
                ToastHub.show(context, context.getString(R.string.toast_volume_start_failed), long = true)
            }
    }

    /** Release bisa tiba sebelum ACTION_START diproses; tunggu sebentar bila perlu. */
    private fun finishVolumeDictation() {
        val deadline = SystemClock.uptimeMillis() + START_FINISH_GRACE_MS
        val finishWhenReady = object : Runnable {
            override fun run() {
                val graph = runCatching { AppGraph.require }.getOrNull() ?: return
                val state = graph.dictation.state.value
                if (state.phase == DictationController.Phase.LISTENING && state.source == DictationController.Source.VOLUME) {
                    graph.dictation.stopAndFinish()
                    if (SystemClock.uptimeMillis() < deadline) mainHandler.postDelayed(this, FINISH_RETRY_MS)
                } else if (state.phase == DictationController.Phase.IDLE && SystemClock.uptimeMillis() < deadline) {
                    mainHandler.postDelayed(this, FINISH_RETRY_MS)
                }
            }
        }
        mainHandler.post(finishWhenReady)
    }

    private fun startObserver() {
        synchronized(this) {
            if (observerStarted) return
            observerStarted = true
        }
        scope.launch {
            val graph = runCatching { AppGraph.require }.getOrNull()
            if (graph == null) {
                VoiceLog.w("Pemicu tombol volume: AppGraph belum siap")
                return@launch
            }
            graph.settings.flow.collect { settings ->
                val nowAssistantEnabled = settings.volumeKeyAssistantEnabled
                if (assistantEnabled && !nowAssistantEnabled) mainHandler.removeCallbacks(assistantHoldRunnable)
                assistantEnabled = nowAssistantEnabled

                val nowDictationEnabled = settings.volumeKeyTriggerEnabled
                if (dictationEnabled && !nowDictationEnabled) mainHandler.removeCallbacks(dictationHoldRunnable)
                dictationEnabled = nowDictationEnabled
            }
        }
    }

    private const val START_FINISH_GRACE_MS = 1_000L
    private const val FINISH_RETRY_MS = 40L
}
