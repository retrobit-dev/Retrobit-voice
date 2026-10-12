package dev.retrobit.voice.assistant

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import dev.retrobit.voice.core.AppGraph
import dev.retrobit.voice.core.VoiceLog

/**
 * Membuat Retrobit Voice bisa dipilih sebagai **asisten digital bawaan Android**
 * (Pengaturan → Aplikasi → Asisten digital).
 *
 * Setelah dipilih, menahan tombol home / tombol asisten akan membuka Retrobit
 * dan langsung mendengarkan — persis perilaku Google Assistant.
 */
class VoiceAssistantService : VoiceInteractionService() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(applicationContext)
    }

    override fun onReady() {
        super.onReady()
        VoiceLog.i("VoiceInteractionService siap")
        AppGraph.require.assistant.startWakeWordIfEnabled()
    }

    override fun onShutdown() {
        AppGraph.require.assistant.stopWakeWord()
        super.onShutdown()
    }

    override fun onCreateSession(): VoiceInteractionSession = RetrobitVoiceSession(this)

    /** Aksi suara yang didukung (ditampilkan di dokumentasi sistem). */
    override fun onGetSupportedVoiceActions(voiceActions: MutableSet<String>): Int {
        voiceActions.addAll(
            listOf(
                "android.voice.action.OPEN_APP",
                "android.voice.action.SEARCH",
                "android.voice.action.CALL",
                "android.voice.action.SEND_MESSAGE",
                "android.voice.action.SET_ALARM",
                "android.voice.action.SET_TIMER",
            ),
        )
        return VoiceInteractionService.SUPPORTED
    }
}

/**
 * Sesi singkat: begitu asisten dipanggil, Retrobit membuka layar asisten
 * dan langsung mendengarkan. Kami sengaja tidak menggambar panel sendiri
 * supaya seluruh pengalaman terkontrol di satu tempat (dan mudah diuji).
 */
class RetrobitVoiceSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        try {
            AppGraph.require.assistant.openAssistantUi(autoListen = true)
        } catch (t: Throwable) {
            VoiceLog.e("Gagal membuka sesi asisten", t)
        }
        hide()
    }
}
