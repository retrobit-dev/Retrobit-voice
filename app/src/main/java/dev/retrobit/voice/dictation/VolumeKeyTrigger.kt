package dev.retrobit.voice.dictation

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import dev.retrobit.voice.R
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.ToastHub
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.DictationTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Logika murni pemicu tombol volume. Sengaja tanpa API Android supaya bisa
 * diuji di JVM (lihat `VolumeTriggerLogicTest`).
 *
 * Aturan:
 *  - mode "tekan dan tahan": tombol DITEKAN = mulai merekam, tombol DILEPAS =
 *    selesai. Peristiwa tombol ditelan (volume tidak ikut berubah) — kalau tidak,
 *    slider volume muncul dan menutupi layar selama dikte.
 *  - mode lain (otomatis saat berhenti bicara / bisa keduanya): tekan singkat =
 *    mulai, tekan singkat lagi = berhenti. Peristiwa tombol TIDAK ditelan
 *    sehingga volume tetap berubah seperti biasa.
 */
class VolumeTriggerLogic(
    /** true = tahan tombol untuk merekam; false = sekali tekan menyalakan/mematikan. */
    var holdToTalk: Boolean = true,
    /** Di atas durasi ini, tekan dianggap "menahan" (bukan ketukan singkat). */
    private val shortPressMaxMs: Long = 700L,
) {

    enum class Action { NONE, START, FINISH, TOGGLE }

    /** [consume] = true berarti peristiwa tombol jangan diteruskan ke sistem. */
    data class Decision(val action: Action, val consume: Boolean)

    private var pressed = false
    private var pressedAtMs = 0L

    val isPressed: Boolean get() = pressed

    fun onDown(atMs: Long, repeatCount: Int): Decision {
        if (!pressed) {
            pressed = true
            pressedAtMs = atMs
            return if (holdToTalk) {
                Decision(Action.START, consume = true)
            } else {
                Decision(Action.NONE, consume = false)
            }
        }
        // Masih ditahan (termasuk auto-repeat): jangan memulai ulang rekaman.
        return Decision(Action.NONE, consume = holdToTalk)
    }

    fun onUp(atMs: Long): Decision {
        if (!pressed) return Decision(Action.NONE, consume = false)
        val heldMs = (atMs - pressedAtMs).coerceAtLeast(0L)
        pressed = false
        if (holdToTalk) return Decision(Action.FINISH, consume = true)
        // Ketukan singkat = pemicu; tombol yang ditahan lama kemungkinan besar
        // memang untuk mengatur volume, jadi dibiarkan saja.
        return if (heldMs <= shortPressMaxMs) {
            Decision(Action.TOGGLE, consume = false)
        } else {
            Decision(Action.NONE, consume = false)
        }
    }

    fun reset() {
        pressed = false
        pressedAtMs = 0L
    }

    /** Terapkan mode dari pengaturan; membatalkan tombol yang sedang "ditahan". */
    fun applyMode(holdToTalk: Boolean) {
        if (this.holdToTalk != holdToTalk) {
            this.holdToTalk = holdToTalk
            reset()
        }
    }
}

/**
 * Pemicu dikte lewat tombol volume, memakai jalur yang sudah ada:
 * [dev.retrobit.voice.dictation.a11y.TextInsertAccessibilityService] menerima
 * peristiwa tombol (flag `flagRequestFilterKeyEvents`) lalu meneruskannya ke sini.
 *
 * Kenapa aksesibilitas? Android tidak menyediakan API publik lain untuk membaca
 * tombol volume tanpa menelan penekanannya; selain itu layanan yang sama juga
 * sudah dipakai untuk menempelkan hasil dikte.
 */
object VolumeKeyTrigger {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val logic = VolumeTriggerLogic()

    @Volatile
    private var enabled = false

    private var observerStarted = false

    /** Dipanggil layanan aksesibilitas saat tersambung. Aman dipanggil berkali-kali. */
    fun attach() = startObserver()

    /**
     * Meneruskan peristiwa tombol volume. Mengembalikan true kalau peristiwa
     * ditelan (volume tidak berubah).
     */
    fun onKeyEvent(context: Context, event: KeyEvent?): Boolean {
        if (event == null) return false
        val isVolume = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolume) return false
        if (!enabled) {
            logic.reset()
            return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val decision = logic.onDown(SystemClock.uptimeMillis(), event.repeatCount)
                perform(context, decision.action)
                decision.consume
            }
            KeyEvent.ACTION_UP -> {
                val decision = logic.onUp(SystemClock.uptimeMillis())
                perform(context, decision.action)
                decision.consume
            }
            else -> false
        }
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
                logic.applyMode(settings.dictationTrigger == DictationTrigger.HOLD_TO_TALK)
                val nowEnabled = settings.volumeKeyTriggerEnabled
                if (enabled && !nowEnabled) logic.reset()
                enabled = nowEnabled
            }
        }
    }

    private fun perform(context: Context, action: VolumeTriggerLogic.Action) {
        if (action == VolumeTriggerLogic.Action.NONE) return
        val graph = runCatching { AppGraph.require }.getOrNull() ?: return

        when (action) {
            VolumeTriggerLogic.Action.START, VolumeTriggerLogic.Action.TOGGLE -> {
                when (graph.dictation.state.value.phase) {
                    DictationController.Phase.LISTENING -> {
                        // Ketukan kedua (mode sekali tekan) = selesai. Di mode tahan,
                        // pelepasan tombol yang mengakhiri, bukan penekanan berikutnya.
                        if (action == VolumeTriggerLogic.Action.TOGGLE) graph.dictation.stopAndFinish()
                    }
                    DictationController.Phase.TRANSCRIBING,
                    DictationController.Phase.CLEANING,
                    DictationController.Phase.INSERTING,
                    -> VoiceLog.d("Tombol volume diabaikan: dikte sedang diproses")
                    DictationController.Phase.IDLE,
                    DictationController.Phase.DONE,
                    DictationController.Phase.ERROR,
                    -> startDictation(context, graph)
                }
            }

            VolumeTriggerLogic.Action.FINISH -> graph.dictation.stopAndFinish()
        }
    }

    private fun startDictation(context: Context, graph: AppGraph) {
        if (!graph.dictation.hasMicrophonePermission()) {
            ToastHub.show(context, context.getString(R.string.toast_volume_needs_mic), long = true)
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
}
