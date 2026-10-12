package dev.retrobit.voice.stt

import android.content.Context
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.Transcript
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.CloudSttProvider
import dev.retrobit.voice.data.SettingsRepository
import dev.retrobit.voice.data.SttEngineChoice

/**
 * Orkestrator STT hybrid.
 *
 * Urutan percobaan pada mode AUTO (bawaan):
 *   1. on-device  → privat, gratis, tanpa internet (Android 12+, idealnya 13+)
 *   2. cloud      → akurasi tertinggi, dipakai otomatis kalau langkah 1 gagal
 *   3. error      → pesan yang menjelaskan penyebab tiap percobaan
 *
 * Pengguna juga bisa memaksa salah satu mesin dari Pengaturan.
 */
class HybridSttEngine(private val context: Context) {

    private val onDevice = AndroidOnDeviceSttEngine(context)
    private val system = AndroidSystemSttEngine(context)

    fun onDeviceAvailable(): Boolean = onDevice.isAvailable()
    fun onDeviceFileModeAvailable(): Boolean = onDevice.isAvailable() && onDevice.supportsFileInjection()
    fun systemAvailable(): Boolean = system.isAvailable()

    /** Ringkasan kondisi mesin untuk layar Beranda/Diagnostik. */
    fun statusSummary(settings: AppSettings): String = buildString {
        append("On-device: ")
        append(
            when {
                !onDevice.isAvailable() -> "tidak tersedia"
                !onDevice.supportsFileInjection() -> "tersedia (butuh Android 13+ untuk mode berkas)"
                else -> "siap"
            },
        )
        append(" · Cloud: ")
        append(if (settings.cloudSttReady) settings.cloudSttProvider.name.lowercase() + " siap" else "belum ada API key")
        append(" · Sistem: ")
        append(if (system.isAvailable()) "siap" else "tidak tersedia")
    }

    private fun cloudEngine(settings: AppSettings): CloudSttEngine = CloudSttEngine(
        provider = settings.cloudSttProvider,
        apiKey = settings.cloudSttApiKey,
        baseUrl = settings.cloudSttBaseUrl.ifBlank { SettingsRepository.defaultBaseUrl(settings.cloudSttProvider) },
        model = settings.cloudSttModel.ifBlank { SettingsRepository.defaultModel(settings.cloudSttProvider) },
    )

    /**
     * Transkripsi dari berkas rekaman (dipakai mode dikte).
     * [onStage] memberi tahu UI tahap yang sedang berjalan.
     */
    suspend fun transcribe(
        recording: PcmRecording,
        settings: AppSettings,
        onStage: (String) -> Unit = {},
        onPartial: (String) -> Unit = {},
    ): Transcript {
        if (recording.durationMs < 250) {
            throw SttFailedException("Rekaman terlalu pendek — tekan mikrofon lalu bicara")
        }

        when (settings.sttEngine) {
            SttEngineChoice.ON_DEVICE -> {
                onStage("Memproses di perangkat…")
                return onDevice.transcribe(recording, settings.dictationLanguage, onPartial)
            }
            SttEngineChoice.CLOUD -> {
                if (!settings.cloudSttReady) {
                    throw SttUnavailableException("Mesin cloud dipilih tetapi API key belum diisi di Pengaturan")
                }
                onStage("Mengirim ke ${settings.cloudSttProvider.name.lowercase()}…")
                return cloudEngine(settings).transcribe(recording, settings.dictationLanguage, onPartial)
            }
            // Mode "bawaan Android" untuk berkas tetap dicoba lewat mekanisme on-device.
            SttEngineChoice.SYSTEM_LIVE -> {
                onStage("Memproses di perangkat…")
                return onDevice.transcribe(recording, settings.dictationLanguage, onPartial)
            }
            SttEngineChoice.AUTO -> Unit // lanjut ke kaskade di bawah
        }

        val attempts = mutableListOf<Pair<String, String>>()

        onStage("Mencoba on-device…")
        try {
            val result = onDevice.transcribe(recording, settings.dictationLanguage, onPartial)
            VoiceLog.i("Transkripsi on-device berhasil (${result.latencyMs} ms)")
            return result
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            VoiceLog.w("On-device gagal: $reason")
            attempts += "on-device" to reason
        }

        if (settings.cloudSttReady) {
            onStage("Mengirim ke ${settings.cloudSttProvider.name.lowercase()}…")
            try {
                val result = cloudEngine(settings).transcribe(recording, settings.dictationLanguage, onPartial)
                VoiceLog.i("Transkripsi cloud berhasil (${result.latencyMs} ms)")
                return result
            } catch (t: Throwable) {
                val reason = t.message ?: t.javaClass.simpleName
                VoiceLog.w("Cloud gagal: $reason")
                attempts += settings.cloudSttProvider.name.lowercase() to reason
            }
        } else {
            attempts += "cloud" to "API key belum diisi"
        }

        throw SttAllFailedException(attempts)
    }

    /** Sesi siaran langsung memakai recognizer sistem (tanpa rekaman sendiri). */
    suspend fun listenLive(
        settings: AppSettings,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        timeoutMs: Long = 20_000L,
    ): Transcript {
        if (!system.isAvailable()) throw SttUnavailableException("Recognizer sistem tidak tersedia")
        return system.listen(
            language = settings.dictationLanguage,
            onPartial = onPartial,
            onLevel = onLevel,
            preferOnDevice = settings.sttEngine != SttEngineChoice.CLOUD,
            timeoutMs = timeoutMs,
        )
    }

    companion object {
        val CLOUD_PROVIDERS: List<CloudSttProvider> = CloudSttProvider.entries.toList()
    }
}
