package dev.retrobit.voice.stt

import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.Transcript

/** Kontrak semua mesin pengenalan suara. */
interface SttEngine {
    val id: String
    suspend fun transcribe(recording: PcmRecording, language: String, onPartial: (String) -> Unit = {}): Transcript
}

/** Mesin tidak tersedia di perangkat ini (mis. recognizer on-device belum ada). */
class SttUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Mesin tersedia tapi gagal memproses (jaringan, kuota, izin, bahasa tidak didukung). */
class SttFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Semua mesin gagal — berisi ringkasan setiap percobaan untuk ditampilkan ke pengguna. */
class SttAllFailedException(val attempts: List<Pair<String, String>>) :
    Exception(attempts.joinToString("; ") { "${it.first}: ${it.second}" })

/** BCP-47 ("id-ID") → kode 2 huruf yang diminta API cloud ("id"). */
fun bcp47ToIso639(language: String): String = language.substringBefore('-').lowercase()

/** BCP-47 → nama bahasa untuk prompt LLM. */
fun humanLanguageName(language: String): String = when (bcp47ToIso639(language)) {
    "id" -> "Bahasa Indonesia"
    "en" -> "English"
    "ms" -> "Bahasa Melayu"
    "jv" -> "Basa Jawa"
    "su" -> "Basa Sunda"
    "zh" -> "Chinese"
    "ja" -> "Japanese"
    "ar" -> "Arabic"
    "es" -> "Spanish"
    else -> "Bahasa Indonesia"
}
