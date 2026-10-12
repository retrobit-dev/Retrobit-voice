package dev.retrobit.voice.ai

import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.data.AppSettings
import dev.retrobit.voice.data.LlmProvider
import dev.retrobit.voice.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Klien LLM untuk:
 *  - "AI cleanup" (merapikan hasil dikte),
 *  - pemahaman perintah asisten + jawaban bebas.
 *
 * Dua bentuk API didukung:
 *  - OpenAI-compatible chat completions (OpenAI, Groq, Ollama lokal, LM Studio, dsb.)
 *  - Google Gemini generateContent
 */
class LlmClient {

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ResponseFormat(val type: String)

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.2,
        val response_format: ResponseFormat? = null,
    )

    class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Kirim percakapan ke LLM dan kembalikan teks jawabannya. */
    suspend fun complete(
        settings: AppSettings,
        systemPrompt: String,
        userText: String,
        history: List<Pair<String, String>> = emptyList(),
        jsonMode: Boolean = false,
        temperature: Double = 0.2,
    ): String = withContext(Dispatchers.IO) {
        if (!settings.llmReady) throw LlmException("LLM belum dikonfigurasi")

        val messages = buildList {
            add(ChatMessage("system", systemPrompt))
            history.takeLast(8).forEach { (role, content) -> add(ChatMessage(role, content)) }
            add(ChatMessage("user", userText))
        }

        when (settings.llmProvider) {
            LlmProvider.OPENAI, LlmProvider.OLLAMA -> openAiCompatible(settings, messages, jsonMode, temperature)
            LlmProvider.GEMINI -> gemini(settings, systemPrompt, history, userText, temperature)
            LlmProvider.OFF -> throw LlmException("LLM dimatikan")
        }
    }

    private fun openAiCompatible(
        settings: AppSettings,
        messages: List<ChatMessage>,
        jsonMode: Boolean,
        temperature: Double,
    ): String {
        val base = settings.llmBaseUrl.ifBlank {
            SettingsRepository.defaultLlmBaseUrl(settings.llmProvider)
        }.trimEnd('/')
        val model = settings.llmModel.ifBlank { SettingsRepository.defaultLlmModel(settings.llmProvider) }

        val payload = Http.json.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(
                model = model,
                messages = messages,
                temperature = temperature,
                response_format = if (jsonMode) ResponseFormat("json_object") else null,
            ),
        )

        val builder = Request.Builder()
            .url("$base/v1/chat/completions")
            .post(payload.toRequestBody("application/json".toMediaType()))
        if (settings.llmApiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${settings.llmApiKey}")

        return Http.execute(builder.build()) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VoiceLog.e("LLM gagal (${response.code}): ${body.take(300)}")
                throw LlmException(mapError(response.code, body))
            }
            runCatching {
                Http.json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("message")?.jsonObject
                    ?.get("content")?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: throw LlmException("Jawaban LLM kosong/tidak terbaca")
        }
    }

    private fun gemini(
        settings: AppSettings,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userText: String,
        temperature: Double,
    ): String {
        val base = settings.llmBaseUrl.ifBlank {
            SettingsRepository.defaultLlmBaseUrl(LlmProvider.GEMINI)
        }.trimEnd('/')
        val model = settings.llmModel.ifBlank { SettingsRepository.defaultLlmModel(LlmProvider.GEMINI) }

        fun content(role: String?, text: String): JsonObject = buildJsonObject {
            putJsonArray("parts") { addJsonObject { put("text", text) } }
            if (role != null) put("role", role)
        }

        val payload = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
            }
            putJsonArray("contents") {
                history.takeLast(8).forEach { (role, text) ->
                    add(content(if (role == "assistant") "model" else "user", text))
                }
                add(content("user", userText))
            }
            putJsonObject("generationConfig") { put("temperature", temperature) }
        }

        val request = Request.Builder()
            .url("$base/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", settings.llmApiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return Http.execute(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VoiceLog.e("Gemini gagal (${response.code}): ${body.take(300)}")
                throw LlmException(mapError(response.code, body))
            }
            runCatching {
                Http.json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: throw LlmException("Jawaban Gemini kosong/tidak terbaca")
        }
    }

    private fun mapError(code: Int, body: String): String = when (code) {
        401, 403 -> "API key LLM ditolak ($code)."
        402 -> "Kredit penyedia LLM habis (402)."
        404 -> "Model LLM tidak ditemukan (404). Periksa nama model."
        429 -> "Rate limit / kuota LLM tercapai (429)."
        in 500..599 -> "Server LLM bermasalah ($code)."
        else -> "LLM gagal (HTTP $code): ${body.take(160)}"
    }
}
