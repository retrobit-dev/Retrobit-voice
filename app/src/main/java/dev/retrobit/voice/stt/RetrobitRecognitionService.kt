package dev.retrobit.voice.stt

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.retrobit.voice.core.VoiceLog

/**
 * Implementasi `RecognitionService` minimal.
 *
 * Diperlukan karena `voice-interaction-service` (agar Retrobit bisa dipilih
 * sebagai asisten digital bawaan) mewajibkan atribut `recognitionService`.
 *
 * Catatan desain: aplikasi ini TIDAK memakai layanan ini untuk transkripsi —
 * kita memanggil recognizer sistem langsung. Sejak Android 12 ke atas, sebuah
 * RecognitionService pihak ketiga yang memanggil recognizer lain justru akan
 * ditolak framework, jadi lebih jujur melaporkan error yang jelas daripada
 * tampak "berfungsi" tetapi selalu gagal.
 *
 * Kalau nanti ingin menyalurkan transkripsi lewat jalur ini (mis. untuk
 * integrasi pihak ketiga), implementasikan [onStartListening] dengan mesin
 * on-device atau cloud yang sudah ada di paket ini.
 */
class RetrobitRecognitionService : RecognitionService() {

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        VoiceLog.w("RecognitionService dipanggil pihak ketiga; jalur ini belum didukung")
        runCatching {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    override fun onStopListening(listener: Callback?) {
        runCatching { listener?.endOfSpeech() }
    }

    override fun onCancel(listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_CLIENT) }
    }
}
