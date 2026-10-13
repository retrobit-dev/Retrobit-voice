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
 * Setelah dipilih, menahan tombol Home/tombol asisten memulai sesi dengar.
 * Jika izin overlay dan mikrofon tersedia, popup Retrobit tampil tanpa mengganti
 * aplikasi yang sedang dibuka; layar asisten menjadi fallback bila izin belum siap.
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
 * Sesi singkat: begitu asisten dipanggil, mulai mendengarkan dan serahkan
 * presentasi ke popup overlay Retrobit. Jika izin overlay/mikrofon belum siap,
 * AssistantSession membuka layar asisten sebagai fallback.
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
