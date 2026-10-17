package dev.retrobit.voice.speaker

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.retrobit.voice.core.VoiceLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * On-device ECAPA-TDNN speaker embedding model. The pinned, CC BY 4.0 model is fetched only after
 * the user explicitly starts local enrollment; microphone audio is never part of that request.
 */
class SpeakerEmbeddingModel(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelDirectory = File(appContext.filesDir, "speaker-model")
    private val modelFile = File(modelDirectory, MODEL_FILE_NAME)
    private val downloadMutex = Mutex()
    private val sessionMutex = Mutex()
    private var modelVerified = false
    private var session: OrtSession? = null
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun isModelDownloaded(): Boolean = modelFile.exists() && modelFile.length() > 0L

    suspend fun prepareModel(onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }) {
        val model = ensureModelFile(onProgress)
        // ONNX session creation can take several seconds on a phone; never run it on the UI thread.
        withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                if (session == null) session = createSession(model)
            }
        }
    }

    suspend fun embeddingFromWav(
        wavFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): FloatArray {
        val normalizedSpeech = withContext(Dispatchers.Default) {
            SpeakerAudioFeatures.trimSpeech(SpeakerAudioFeatures.readPcm16MonoWav(wavFile))
        }
        val features = withContext(Dispatchers.Default) { SpeakerAudioFeatures.computeFbank(normalizedSpeech) }
        val model = ensureModelFile(onProgress)
        return withContext(Dispatchers.Default) {
            sessionMutex.withLock {
                val activeSession = session ?: createSession(model).also { session = it }
                infer(activeSession, features)
            }
        }
    }

    private suspend fun ensureModelFile(onProgress: (Long, Long) -> Unit): File = downloadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (modelVerified && modelFile.exists()) return@withContext modelFile
            if (modelFile.exists() && sha256(modelFile) == MODEL_SHA256) {
                modelVerified = true
                return@withContext modelFile
            }
            modelFile.delete()
            modelDirectory.mkdirs()
            val temporary = File(modelDirectory, "$MODEL_FILE_NAME.part")
            temporary.delete()

            val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", "Retrobit-Voice/${appContext.packageName}")
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("Unduhan model speaker gagal (HTTP ${connection.responseCode})")
                }
                val totalBytes = connection.contentLengthLong
                if (totalBytes > MAX_MODEL_BYTES) throw IllegalStateException("Ukuran model speaker melebihi batas")
                var downloaded = 0L
                connection.inputStream.use { input ->
                    FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            downloaded += count
                            if (downloaded > MAX_MODEL_BYTES) throw IllegalStateException("Unduhan model speaker terlalu besar")
                            output.write(buffer, 0, count)
                            onProgress(downloaded, totalBytes)
                        }
                        output.fd.sync()
                    }
                }
                if (downloaded == 0L || sha256(temporary) != MODEL_SHA256) {
                    throw IllegalStateException("Verifikasi SHA-256 model speaker gagal")
                }
                if (!temporary.renameTo(modelFile)) throw IllegalStateException("Model speaker tidak dapat disimpan")
                modelVerified = true
                VoiceLog.i("Model speaker lokal siap (${downloaded / (1024 * 1024)} MiB)")
                onProgress(downloaded, if (totalBytes > 0) totalBytes else downloaded)
                modelFile
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun createSession(file: File): OrtSession {
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        return try {
            environment.createSession(file.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun infer(activeSession: OrtSession, features: FloatArray): FloatArray {
        if (features.isEmpty() || features.size % SpeakerAudioFeatures.FEATURE_BINS != 0) {
            throw SpeakerAudioFeatures.InvalidAudioException("Fitur audio untuk speaker tidak valid")
        }
        val frames = features.size / SpeakerAudioFeatures.FEATURE_BINS
        val input = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(features),
            longArrayOf(1L, frames.toLong(), SpeakerAudioFeatures.FEATURE_BINS.toLong()),
        )
        try {
            val output = activeSession.run(mapOf(activeSession.inputNames.first() to input))
            try {
                val values = mutableListOf<Float>()
                flatten(output[0].value, values)
                if (values.size != EMBEDDING_DIMENSION) {
                    throw IllegalStateException("Dimensi embedding model speaker tidak didukung: ${values.size}")
                }
                return SpeakerAudioFeatures.normalize(values.toFloatArray())
                    ?: throw IllegalStateException("Model speaker menghasilkan embedding tidak valid")
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
    }

    private fun flatten(value: Any, result: MutableList<Float>) {
        when (value) {
            is FloatArray -> value.forEach(result::add)
            is Array<*> -> value.forEach { child -> if (child != null) flatten(child, result) }
            is Float -> result += value
            else -> throw IllegalStateException("Bentuk output model speaker tidak dikenali")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    override fun close() {
        sessionMutex.tryLock().let { locked ->
            if (locked) {
                try {
                    session?.close()
                    session = null
                } finally {
                    sessionMutex.unlock()
                }
            }
        }
    }

    private companion object {
        // Immutable upstream revision; model card/license: Wespeaker/wespeaker-ecapa-tdnn512-LM, CC BY 4.0.
        const val MODEL_URL = "https://huggingface.co/Wespeaker/wespeaker-ecapa-tdnn512-LM/resolve/a2f3dcb1c8702caccc7a55ceb57f5e8d1842112b/voxceleb_ECAPA512_LM.onnx?download=true"
        const val MODEL_SHA256 = "d71b85d9b48058ef68004f04f1b78acebefb9dfcf542e19b976a12a5ad1f10b0"
        const val MODEL_FILE_NAME = "wespeaker-ecapa-tdnn512-lm.onnx"
        const val MAX_MODEL_BYTES = 80L * 1024L * 1024L
        const val EMBEDDING_DIMENSION = 192
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
