package dev.retrobit.voice.stt

import dev.retrobit.voice.ai.Http
import dev.retrobit.voice.core.PcmRecording
import dev.retrobit.voice.core.Transcript
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.CloudSttProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Mesin STT cloud (BYOK — API key milik pengguna, disimpan terenkripsi).
 *
 * Mendukung dua gaya API:
 *  - OpenAI-compatible (OpenAI, Groq): POST /v1/audio/transcriptions (multipart)
 *  - Deepgram: POST /v1/listen (badan biner WAV)
 *
 * Audio dikirim apa adanya dari rekaman lokal (16 kHz mono WAV).
 */
class CloudSttEngine(
    private val provider: CloudSttProvider,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) : SttEngine {

    override val id: String = "cloud:" + provider.name.lowercase()

    override suspend fun transcribe(
        recording: PcmRecording,
        language: String,
        onPartial: (String) -> Unit,
    ): Transcript = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw SttUnavailableException("API key untuk $provider belum diisi")
        val started = System.currentTimeMillis()
        val text = when (provider) {
            CloudSttProvider.OPENAI, CloudSttProvider.GROQ -> openAiCompatible(recording, language)
            CloudSttProvider.DEEPGRAM -> deepgram(recording, language)
        }
        Transcript(
            text = text.trim(),
            engine = id,
            latencyMs = System.currentTimeMillis() - started,
            language = language,
        )
    }

    private fun normalizedBase(): String = baseUrl.trim().trimEnd('/').ifBlank {
        when (provider) {
            CloudSttProvider.OPENAI -> "https://api.openai.com"
            CloudSttProvider.GROQ -> "https://api.groq.com/openai"
            CloudSttProvider.DEEPGRAM -> "https://api.deepgram.com"
        }
    }

    private fun openAiCompatible(recording: PcmRecording, language: String): String {
        val effectiveModel = model.ifBlank {
            if (provider == CloudSttProvider.GROQ) "whisper-large-v3-turbo" else "gpt-4o-mini-transcribe"
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                recording.wavFile.name,
                recording.wavFile.asRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", effectiveModel)
            .addFormDataPart("language", bcp47ToIso639(language))
            .addFormDataPart("response_format", "json")
            .build()

        val request = Request.Builder()
            .url("${normalizedBase()}/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        return Http.execute(request) { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VoiceLog.e("STT cloud gagal (${response.code}): ${payload.take(300)}")
                throw SttFailedException(mapCloudError(response.code, payload))
            }
            parseText(payload)
        }
    }

    private fun deepgram(recording: PcmRecording, language: String): String {
        val effectiveModel = model.ifBlank { "nova-2" }
        val url = "${normalizedBase()}/v1/listen" +
            "?model=$effectiveModel" +
            "&language=${bcp47ToIso639(language)}" +
            "&smart_format=true&punctuate=true"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .addHeader("Content-Type", "audio/wav")
            .post(recording.wavFile.readBytes().toRequestBody("audio/wav".toMediaType()))
            .build()

        return Http.execute(request) { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VoiceLog.e("Deepgram gagal (${response.code}): ${payload.take(300)}")
                throw SttFailedException(mapCloudError(response.code, payload))
            }
            runCatching {
                Http.json.parseToJsonElement(payload).jsonObject
                    .get("results")?.jsonObject
                    ?.get("channels")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("transcript")?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
        }
    }

    private fun parseText(payload: String): String = runCatching {
        val obj: JsonObject = Http.json.parseToJsonElement(payload).jsonObject
        obj["text"]?.jsonPrimitive?.content.orEmpty()
    }.getOrElse { payload.take(500) }

    private fun mapCloudError(code: Int, payload: String): String = when (code) {
        401, 403 -> "API key ditolak ($code). Periksa kembali di Pengaturan."
        402 -> "Kuota/kredit penyedia habis (402)."
        413 -> "Audio terlalu panjang untuk sekali kirim (413)."
        429 -> "Terlalu banyak permintaan / kuota habis (429). Coba lagi sebentar."
        in 500..599 -> "Server penyedia bermasalah ($code)."
        else -> "Gagal (HTTP $code): ${payload.take(160)}"
    }
}
