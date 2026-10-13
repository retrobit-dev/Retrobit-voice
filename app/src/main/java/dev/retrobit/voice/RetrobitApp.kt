package dev.retrobit.voice

import android.app.Application
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.dictation.overlay.OverlayBubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Titik awal aplikasi: menyiapkan service locator, memuat riwayat,
 * dan memulihkan fitur latar belakang yang sebelumnya diaktifkan pengguna
 * (bubble melayang, kata kunci panggilan).
 */
class RetrobitApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val graph = AppGraph.init(this)
        graph.tts.init()

        appScope.launch {
            graph.history.load()
            val settings = graph.settings.current()

            if (settings.overlayBubbleEnabled && OverlayBubbleService.hasPermission(this@RetrobitApp)) {
                runCatching { OverlayBubbleService.start(this@RetrobitApp) }
                    .onFailure { VoiceLog.e("Gagal memulihkan bubble", it) }
            }
            if (settings.wakeWordEnabled) {
                graph.assistant.startWakeWordIfEnabled()
            }
        }

        appScope.launch {
            var assistantWasActive = false
            graph.assistant.state.collect { state ->
                val assistantActive = state.listening || state.thinking
                if (
                    assistantActive && !assistantWasActive &&
                    OverlayBubbleService.hasPermission(this@RetrobitApp) &&
                    graph.dictation.hasMicrophonePermission()
                ) {
                    OverlayBubbleService.showAssistantPopup(this@RetrobitApp)
                }
                assistantWasActive = assistantActive
            }
        }

        installCrashLogger()
    }

    /** Catat crash ke VoiceLog supaya bisa dibaca dari layar Diagnostik. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            VoiceLog.e("Crash di thread ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }
}
