package dev.retrobit.voice.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dev.retrobit.voice.core.EnrollmentCrashCheckpoint
import dev.retrobit.voice.core.VoiceLog
import dev.retrobit.voice.core.toDirectNativeFloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Local personal wake-word matching backed by Google's small speech-embedding ONNX model. */
class LocalWakeWordKws(context: Context) {
    private val appContext = context.applicationContext
    private val profileStore = WakeWordProfileStore(appContext.noBackupFilesDir)
    private val embeddingModel = WakeWordEmbeddingModel(appContext)

    suspend fun prepare() = embeddingModel.prepare()

    suspend fun hasProfileForPhrase(phrase: String): Boolean = withContext(Dispatchers.IO) {
        profileStore.hasProfileForPhrase(phrase)
    }

    suspend fun profileForPhrase(phrase: String): WakeWordProfile? = withContext(Dispatchers.IO) {
        profileStore.readProfile(phrase)
    }

    suspend fun createTemplate(samples: FloatArray): WakeWordEmbeddingSequence =
        embeddingModel.extractSpeech(samples)

    /** Best distance to any of the enrolled samples, following the local-wake template method. */
    suspend fun distanceToProfile(
        audioWindow: FloatArray,
        profile: WakeWordProfile,
    ): Float {
        val actual = embeddingModel.extractSpeech(audioWindow)
        return profile.templates.minOf { reference ->
            WakeWordAudioFeatures.dtwCosineNormalizedDistance(actual, reference)
        }
    }
}

/** ONNX Runtime wrapper; the speech model and all input audio stay on this device. */
private class WakeWordEmbeddingModel(private val context: Context) {
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    @Volatile
    private var session: OrtSession? = null

    // ONNX Runtime requires SessionOptions to outlive every session created from it.
    @Volatile
    private var sessionOptions: OrtSession.SessionOptions? = null

    suspend fun prepare() {
        ensureSession()
    }

    suspend fun extractSpeech(samples: FloatArray): WakeWordEmbeddingSequence {
        EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "trimming-speech")
        val speech = withContext(Dispatchers.Default) { WakeWordAudioFeatures.trimSpeech(samples) }
        EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "waiting-for-onnx-session")
        val activeSession = ensureSession()
        return withContext(Dispatchers.Default) {
            inferenceMutex.lock()
            try {
                runInference(activeSession, speech)
            } finally {
                inferenceMutex.unlock()
            }
        }
    }

    private suspend fun ensureSession(): OrtSession {
        session?.let { return it }
        return loadMutex.withLock {
            session?.let { return@withLock it }
            val modelFile = withContext(Dispatchers.IO) { ensureModelFile() }
            withContext(Dispatchers.Default) { createSession(modelFile).also { session = it } }
        }
    }

    private fun ensureModelFile(): File {
        if (!modelDirectory.exists() && !modelDirectory.mkdirs()) {
            throw IllegalStateException("Folder model wake word tidak dapat dibuat")
        }
        if (modelFile.exists() && modelFile.length() in 1L..MAX_MODEL_BYTES && sha256(modelFile) == MODEL_SHA256) {
            return modelFile
        }
        modelFile.delete()
        val temporary = File(modelDirectory, "$MODEL_FILE_NAME.part")
        temporary.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            context.assets.open(MODEL_ASSET).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > MAX_MODEL_BYTES) throw IllegalStateException("Model wake word terlalu besar")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (written == 0L || digest.digest().toHex() != MODEL_SHA256) {
                throw IllegalStateException("Verifikasi model wake word gagal")
            }
            if (!temporary.renameTo(modelFile)) throw IllegalStateException("Model wake word tidak dapat disimpan")
            VoiceLog.i("Model embedding wake word lokal siap (${written / (1024 * 1024)} MiB)")
            return modelFile
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun createSession(file: File): OrtSession {
        val options = OrtSession.SessionOptions()
        return try {
            options.setIntraOpNumThreads(1)
            options.setInterOpNumThreads(1)
            environment.createSession(file.absolutePath, options).also {
                sessionOptions = options
            }
        } catch (error: Throwable) {
            options.close()
            throw error
        }
    }

    private suspend fun runInference(activeSession: OrtSession, speech: FloatArray): WakeWordEmbeddingSequence {
        if (speech.size < WakeWordAudioFeatures.MIN_MODEL_SAMPLES || speech.any { !it.isFinite() }) {
            throw WakeWordAudioFeatures.InvalidAudioException("Durasi audio wake word tidak cukup")
        }
        val inputName = activeSession.inputNames.firstOrNull()
            ?: throw IllegalStateException("Model wake word tidak memiliki input audio")
        checkNotNull(sessionOptions) { "Opsi sesi ONNX wake word sudah dilepas" }
        EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "creating-onnx-input-tensor")
        val input = OnnxTensor.createTensor(
            environment,
            speech.toDirectNativeFloatBuffer(),
            longArrayOf(1L, speech.size.toLong()),
        )
        try {
            EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "running-onnx-session")
            val output = activeSession.run(mapOf(inputName to input))
            try {
                EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "reading-onnx-output")
                val flattened = ArrayList<Float>()
                flatten(output[0].value, flattened)
                val dimension = WakeWordAudioFeatures.EMBEDDING_DIMENSION
                if (flattened.isEmpty() || flattened.size % dimension != 0) {
                    throw IllegalStateException("Dimensi output model wake word tidak didukung")
                }
                val normalized = FloatArray(flattened.size)
                val frameCount = flattened.size / dimension
                for (frame in 0 until frameCount) {
                    val offset = frame * dimension
                    var squaredMagnitude = 0.0
                    for (feature in 0 until dimension) {
                        val value = flattened[offset + feature]
                        if (!value.isFinite()) throw IllegalStateException("Model wake word menghasilkan nilai tidak valid")
                        squaredMagnitude += value.toDouble() * value.toDouble()
                    }
                    if (squaredMagnitude <= 1e-24) {
                        throw IllegalStateException("Model wake word menghasilkan embedding kosong")
                    }
                    val magnitude = kotlin.math.sqrt(squaredMagnitude).toFloat()
                    for (feature in 0 until dimension) {
                        normalized[offset + feature] = flattened[offset + feature] / magnitude
                    }
                }
                EnrollmentCrashCheckpoint.mark(context, "wake-word-example", "onnx-output-normalized")
                return WakeWordEmbeddingSequence(frameCount, normalized)
            } finally {
                output.close()
            }
        } finally {
            input.close()
        }
    }

    private fun flatten(value: Any, output: MutableList<Float>) {
        when (value) {
            is FloatArray -> value.forEach(output::add)
            is Array<*> -> value.forEach { child -> if (child != null) flatten(child, output) }
            is Float -> output += value
            else -> throw IllegalStateException("Bentuk output model wake word tidak dikenali")
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
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private val modelDirectory: File
        get() = File(context.noBackupFilesDir, MODEL_DIRECTORY_NAME)

    private val modelFile: File
        get() = File(modelDirectory, MODEL_FILE_NAME)

    private companion object {
        const val MODEL_ASSET = "wakeword/speech-embedding.onnx"
        const val MODEL_DIRECTORY_NAME = "wake-word-model"
        const val MODEL_FILE_NAME = "speech-embedding.onnx"
        const val MODEL_SHA256 = "0140c2b1455c016783f9b859e16f47b5187c69079503cd053f406b86f9094560"
        const val MAX_MODEL_BYTES = 8L * 1024L * 1024L
    }
}
