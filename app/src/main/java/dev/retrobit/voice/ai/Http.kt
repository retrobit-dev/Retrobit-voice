package dev.retrobit.voice.ai

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** Klien HTTP bersama untuk seluruh panggilan jaringan (STT cloud, LLM, TTS opsional). */
object Http {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Eksekusi request dengan penutupan response yang aman. */
    fun <T> execute(request: Request, parse: (Response) -> T): T {
        client.newCall(request).execute().use { response -> return parse(response) }
    }
}
