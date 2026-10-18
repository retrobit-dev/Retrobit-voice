package dev.retrobit.voice.assistant

import java.io.File
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A time-major sequence of normalized 96-dimensional speech embeddings. */
data class WakeWordEmbeddingSequence(
    val frameCount: Int,
    val values: FloatArray,
) {
    init {
        require(frameCount > 0) { "Urutan embedding wake word kosong" }
        require(values.size == frameCount * WakeWordAudioFeatures.EMBEDDING_DIMENSION) {
            "Dimensi embedding wake word tidak valid"
        }
    }
}

/** Pure PCM preparation and DTW matching used by the local, personalized wake-word detector. */
object WakeWordAudioFeatures {
    const val SAMPLE_RATE_HZ = 16_000
    const val EMBEDDING_DIMENSION = 96
    const val MIN_MODEL_SAMPLES = 12_400 // Google speech_embedding needs 775 ms for its first vector.
    private const val VAD_FRAME_SAMPLES = 320 // 20 ms at 16 kHz.
    private const val SILENCE_PADDING_SAMPLES = SAMPLE_RATE_HZ * 150 / 1_000
    private const val MIN_PEAK_AMPLITUDE = 0.003f
    private const val MIN_SPEECH_RMS = 0.0015f

    class InvalidAudioException(message: String) : Exception(message)

    /** Case-, spacing-, punctuation-, and Unicode-normalized key for the enrolled phrase. */
    fun normalizePhrase(phrase: String): String = Normalizer.normalize(phrase, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    /** Read the PCM-16 mono 16 kHz WAV format written by [dev.retrobit.voice.audio.PcmRecorder]. */
    fun readPcm16MonoWav(file: File): FloatArray {
        if (!file.exists() || file.length() !in 44L..(16L * 1024L * 1024L)) {
            throw InvalidAudioException("Ukuran sampel wake word tidak valid")
        }
        val bytes = file.readBytes()
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            throw InvalidAudioException("Format sampel wake word tidak valid")
        }
        val audioFormat = unsignedShortLe(bytes, 20)
        val channels = unsignedShortLe(bytes, 22)
        val sampleRate = intLe(bytes, 24)
        val byteRate = intLe(bytes, 28)
        val blockAlign = unsignedShortLe(bytes, 32)
        val bitsPerSample = unsignedShortLe(bytes, 34)
        val dataBytes = intLe(bytes, 40)
        if (
            audioFormat != 1 || channels != 1 || sampleRate != SAMPLE_RATE_HZ ||
            byteRate != SAMPLE_RATE_HZ * 2 || blockAlign != 2 || bitsPerSample != 16 ||
            dataBytes < 0 || dataBytes % 2 != 0 || dataBytes > bytes.size - 44 ||
            dataBytes < MIN_MODEL_SAMPLES * 2
        ) {
            throw InvalidAudioException("Sampel harus mono PCM 16 kHz dan memuat ucapan yang cukup")
        }
        return FloatArray(dataBytes / 2) { index ->
            val offset = 44 + index * 2
            val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
            value.toShort().toFloat() / 32768f
        }
    }

    /**
     * Trim leading/trailing silence with a small margin and pad short phrases to the model's
     * minimum input length. The returned samples remain normalized to [-1, 1].
     */
    fun trimSpeech(samples: FloatArray): FloatArray {
        if (samples.isEmpty() || samples.any { !it.isFinite() }) {
            throw InvalidAudioException("Audio wake word kosong atau tidak valid")
        }
        val peak = samples.maxOf { kotlin.math.abs(it) }
        if (peak < MIN_PEAK_AMPLITUDE) throw InvalidAudioException("Ucapan tidak terdengar jelas")

        val frameCount = (samples.size + VAD_FRAME_SAMPLES - 1) / VAD_FRAME_SAMPLES
        val frameRms = FloatArray(frameCount) { frameIndex ->
            val start = frameIndex * VAD_FRAME_SAMPLES
            val end = min(samples.size, start + VAD_FRAME_SAMPLES)
            rms(samples, start, end)
        }
        val threshold = max(MIN_SPEECH_RMS, peak * 0.12f)
        val firstFrame = frameRms.indexOfFirst { it >= threshold }
        val lastFrame = frameRms.indexOfLast { it >= threshold }
        if (firstFrame < 0 || lastFrame < firstFrame) {
            throw InvalidAudioException("Tidak ada ucapan jelas; ucapkan frasa lebih dekat ke mikrofon")
        }

        val start = max(0, firstFrame * VAD_FRAME_SAMPLES - SILENCE_PADDING_SAMPLES)
        val end = min(samples.size, (lastFrame + 1) * VAD_FRAME_SAMPLES + SILENCE_PADDING_SAMPLES)
        val speech = samples.copyOfRange(start, end)
        if (speech.size >= MIN_MODEL_SAMPLES) return speech

        // Keep the detected speech centered so short Indonesian phrases still produce embeddings.
        val padded = FloatArray(MIN_MODEL_SAMPLES)
        val offset = (MIN_MODEL_SAMPLES - speech.size) / 2
        speech.copyInto(padded, offset)
        return padded
    }

    /**
     * Cosine DTW distance. Smaller means a closer phrase match; 0 is an exact match.
     * Inputs are time-major: one 96-dimensional embedding per frame.
     */
    fun dtwCosineNormalizedDistance(
        left: WakeWordEmbeddingSequence,
        right: WakeWordEmbeddingSequence,
    ): Float {
        val leftFrames = left.frameCount
        val rightFrames = right.frameCount
        if (leftFrames <= 0 || rightFrames <= 0) return Float.POSITIVE_INFINITY

        var previous = FloatArray(rightFrames + 1) { Float.POSITIVE_INFINITY }
        previous[0] = 0f
        for (leftIndex in 0 until leftFrames) {
            val current = FloatArray(rightFrames + 1) { Float.POSITIVE_INFINITY }
            val leftOffset = leftIndex * EMBEDDING_DIMENSION
            for (rightIndex in 0 until rightFrames) {
                val rightOffset = rightIndex * EMBEDDING_DIMENSION
                val localCost = cosineDistance(left.values, leftOffset, right.values, rightOffset)
                val predecessor = minOf(
                    previous[rightIndex],
                    previous[rightIndex + 1],
                    current[rightIndex],
                )
                current[rightIndex + 1] = localCost + predecessor
            }
            previous = current
        }
        val totalCost = previous[rightFrames]
        if (!totalCost.isFinite()) return Float.POSITIVE_INFINITY
        return totalCost / (leftFrames + rightFrames)
    }

    /**
     * Robust profile score: the middle enrolled-template distance prevents one unusually close
     * sample from triggering a match on its own.
     */
    fun medianDistance(distances: List<Float>): Float =
        distances.sorted().getOrNull(distances.size / 2) ?: Float.POSITIVE_INFINITY

    /** RMS for a full sample array. */
    fun rms(samples: FloatArray): Float = rms(samples, 0, samples.size)

    private fun rms(samples: FloatArray, start: Int, end: Int): Float {
        if (start >= end) return 0f
        var sumSquares = 0.0
        for (index in start until end) {
            val sample = samples[index].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / (end - start)).toFloat()
    }

    private fun cosineDistance(left: FloatArray, leftOffset: Int, right: FloatArray, rightOffset: Int): Float {
        var dot = 0.0
        var leftSquared = 0.0
        var rightSquared = 0.0
        for (dimension in 0 until EMBEDDING_DIMENSION) {
            val leftValue = left[leftOffset + dimension].toDouble()
            val rightValue = right[rightOffset + dimension].toDouble()
            dot += leftValue * rightValue
            leftSquared += leftValue * leftValue
            rightSquared += rightValue * rightValue
        }
        if (leftSquared <= 1e-24 || rightSquared <= 1e-24) return 1f
        val similarity = (dot / sqrt(leftSquared * rightSquared)).coerceIn(-1.0, 1.0)
        return (1.0 - similarity).toFloat()
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun unsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            (bytes[offset + 3].toInt() shl 24)
}
