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

    /**
     * Sistem menanyakan aksi suara mana yang kita dukung. Kontraknya: kembalikan
     * SUBSET dari himpunan yang diberikan (bukan daftar baru).
     *
     * Semua aksi dijalankan sendiri oleh Retrobit lewat Intent resmi, jadi kami
     * melaporkan dukungan untuk aksi yang memang kami tangani dan tidak
     * mengklaim dukungan untuk yang lain.
     */
    override fun onGetSupportedVoiceActions(voiceActions: MutableSet<String>): MutableSet<String> =
        voiceActions.filterTo(mutableSetOf()) { it in SUPPORTED_VOICE_ACTIONS }

    private companion object {
        val SUPPORTED_VOICE_ACTIONS = setOf(
            "android.voice.action.OPEN_APP",
            "android.voice.action.SEARCH",
            "android.voice.action.CALL",
            "android.voice.action.SEND_MESSAGE",
            "android.voice.action.SET_ALARM",
            "android.voice.action.SET_TIMER",
        )
    }
}

/**
 * Sesi voice interaction disediakan lewat service terpisah sesuai arsitektur
 * Android (VoiceInteractionService tidak membuat sesi sendiri).
 */
class RetrobitVoiceInteractionSessionService : android.service.voice.VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = RetrobitVoiceSession(this)
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
            AppGraph.require.assistant.openAssistantUi(autoListen = true, captureScreenContext = true)
        } catch (t: Throwable) {
            VoiceLog.e("Gagal membuka sesi asisten", t)
        }
        hide()
    }
}
