package dev.retrobit.voice.speaker

import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure audio preparation for the local ECAPA speaker model. Input audio is never uploaded here. */
object SpeakerAudioFeatures {
    const val SAMPLE_RATE_HZ = 16_000
    const val FEATURE_BINS = 80
    const val MIN_SPEECH_SAMPLES = SAMPLE_RATE_HZ * 750 / 1_000
    private const val FRAME_LENGTH = 400 // 25 ms at 16 kHz
    private const val FRAME_SHIFT = 160 // 10 ms at 16 kHz
    private const val FFT_SIZE = 512
    private const val VAD_FRAME_SAMPLES = 320 // 20 ms
    private const val PREEMPHASIS = 0.97
    private const val MIN_LOG_ENERGY = 1.1920928955078125e-7 // Float.EPSILON, as in kaldi-native-fbank.

    private val hammingWindow = DoubleArray(FRAME_LENGTH) { index ->
        0.54 - 0.46 * cos(2.0 * PI * index / (FRAME_LENGTH - 1))
    }
    private val melFilters = createMelFilters()

    class InvalidAudioException(message: String) : Exception(message)

    /** Read the PCM-16 mono 16 kHz WAV format written by [dev.retrobit.voice.audio.WavWriter]. */
    fun readPcm16MonoWav(file: File): FloatArray {
        val bytes = file.readBytes()
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            throw InvalidAudioException("Format rekaman speaker tidak valid")
        }
        val channels = unsignedShortLe(bytes, 22)
        val sampleRate = intLe(bytes, 24)
        val bitsPerSample = unsignedShortLe(bytes, 34)
        val dataBytes = intLe(bytes, 40).coerceAtMost(bytes.size - 44)
        if (channels != 1 || sampleRate != SAMPLE_RATE_HZ || bitsPerSample != 16 || dataBytes < FRAME_LENGTH * 2) {
            throw InvalidAudioException("Audio harus mono PCM 16 kHz untuk pemeriksaan speaker")
        }
        val samples = FloatArray(dataBytes / 2)
        for (index in samples.indices) {
            val offset = 44 + index * 2
            val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
            samples[index] = value.toShort().toFloat() / 32768f
        }
        return samples
    }

    /**
     * Trim leading/trailing silence and reject audio without a usable speech span.
     * This is only voice-activity gating; speaker identity comes from the ECAPA model.
     */
    fun trimSpeech(samples: FloatArray): FloatArray {
        if (samples.size < MIN_SPEECH_SAMPLES) throw InvalidAudioException("Ucapkan setidaknya 0,75 detik")
        val frameCount = (samples.size + VAD_FRAME_SAMPLES - 1) / VAD_FRAME_SAMPLES
        val rms = DoubleArray(frameCount)
        for (frame in 0 until frameCount) {
            val start = frame * VAD_FRAME_SAMPLES
            val end = min(samples.size, start + VAD_FRAME_SAMPLES)
            var sumSquares = 0.0
            for (index in start until end) {
                val value = samples[index].toDouble()
                sumSquares += value * value
            }
            rms[frame] = sqrt(sumSquares / max(1, end - start))
        }

        val sorted = rms.sorted()
        val lowerPercentile = sorted[(sorted.size * 0.2).toInt().coerceIn(sorted.indices)]
        val peak = sorted.lastOrNull() ?: 0.0
        var threshold = max(0.004, lowerPercentile * 2.2)
        if (threshold >= peak && peak > 0.0) threshold = max(0.002, peak * 0.45)
        val firstSpeech = rms.indexOfFirst { it >= threshold }
        val lastSpeech = rms.indexOfLast { it >= threshold }
        if (firstSpeech < 0 || lastSpeech < firstSpeech) {
            throw InvalidAudioException("Tidak ada ucapan jelas; coba bicara lebih dekat ke mikrofon")
        }

        val paddingFrames = 8 // 160 ms, helps retain soft consonants around the speech span.
        val start = max(0, firstSpeech - paddingFrames) * VAD_FRAME_SAMPLES
        val end = min(samples.size, (lastSpeech + paddingFrames + 1) * VAD_FRAME_SAMPLES)
        if (end - start < MIN_SPEECH_SAMPLES) {
            throw InvalidAudioException("Ucapan terlalu singkat untuk pemeriksaan speaker")
        }
        return samples.copyOfRange(start, end)
    }

    /** 80-bin log-mel frontend using the published 16 kHz Kaldi FBank settings, then CMN. */
    fun computeFbank(samples: FloatArray): FloatArray {
        if (samples.size < FRAME_LENGTH) throw InvalidAudioException("Audio terlalu pendek untuk ekstraksi suara")
        val frameCount = 1 + (samples.size - FRAME_LENGTH) / FRAME_SHIFT
        val features = FloatArray(frameCount * FEATURE_BINS)
        val real = DoubleArray(FFT_SIZE)
        val imaginary = DoubleArray(FFT_SIZE)
        val power = DoubleArray(FFT_SIZE / 2 + 1)
        val frame = DoubleArray(FRAME_LENGTH)

        for (frameIndex in 0 until frameCount) {
            val offset = frameIndex * FRAME_SHIFT
            var mean = 0.0
            for (index in 0 until FRAME_LENGTH) {
                val value = samples[offset + index].toDouble() * 32768.0
                frame[index] = value
                mean += value
            }
            mean /= FRAME_LENGTH
            real.fill(0.0)
            imaginary.fill(0.0)
            var previous = frame[0] - mean
            for (index in 0 until FRAME_LENGTH) {
                val centered = frame[index] - mean
                val emphasized = if (index == 0) centered * (1.0 - PREEMPHASIS) else centered - PREEMPHASIS * previous
                previous = centered
                real[index] = emphasized * hammingWindow[index]
            }
            fft(real, imaginary)
            for (bin in power.indices) {
                power[bin] = real[bin] * real[bin] + imaginary[bin] * imaginary[bin]
            }

            val outputOffset = frameIndex * FEATURE_BINS
            for (filter in 0 until FEATURE_BINS) {
                var energy = 0.0
                val weights = melFilters[filter]
                for (bin in power.indices) energy += power[bin] * weights[bin]
                features[outputOffset + filter] = ln(max(MIN_LOG_ENERGY, energy)).toFloat()
            }
        }

        // Mean-normalize each mel bin across this utterance (CMN).
        for (bin in 0 until FEATURE_BINS) {
            var sum = 0.0
            for (frameIndex in 0 until frameCount) sum += features[frameIndex * FEATURE_BINS + bin]
            val mean = (sum / frameCount).toFloat()
            for (frameIndex in 0 until frameCount) features[frameIndex * FEATURE_BINS + bin] -= mean
        }
        return features
    }

    fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val squaredMagnitude = vector.sumOf { it.toDouble() * it.toDouble() }
        if (!squaredMagnitude.isFinite() || squaredMagnitude <= 1e-24) return null
        val magnitude = sqrt(squaredMagnitude)
        return FloatArray(vector.size) { index -> (vector[index] / magnitude).toFloat() }
    }

    fun averageNormalized(embeddings: List<FloatArray>): FloatArray? {
        val size = embeddings.firstOrNull()?.size ?: return null
        if (size == 0 || embeddings.any { it.size != size }) return null
        val average = FloatArray(size) { index ->
            (embeddings.sumOf { it[index].toDouble() } / embeddings.size).toFloat()
        }
        return normalize(average)
    }

    fun cosineSimilarity(left: FloatArray, right: FloatArray): Float? {
        if (left.isEmpty() || left.size != right.size) return null
        val normalizedLeft = normalize(left) ?: return null
        val normalizedRight = normalize(right) ?: return null
        return normalizedLeft.indices.sumOf { (normalizedLeft[it] * normalizedRight[it]).toDouble() }
            .toFloat()
            .coerceIn(-1f, 1f)
    }

    private fun createMelFilters(): Array<DoubleArray> {
        val lowMel = hzToMel(20.0)
        val highMel = hzToMel(SAMPLE_RATE_HZ / 2.0)
        val melStep = (highMel - lowMel) / (FEATURE_BINS + 1)
        return Array(FEATURE_BINS) { filter ->
            val leftMel = lowMel + filter * melStep
            val centerMel = lowMel + (filter + 1) * melStep
            val rightMel = lowMel + (filter + 2) * melStep
            DoubleArray(FFT_SIZE / 2 + 1) { bin ->
                val frequency = bin.toDouble() * SAMPLE_RATE_HZ / FFT_SIZE
                val mel = hzToMel(frequency)
                when {
                    mel <= leftMel || mel >= rightMel -> 0.0
                    mel <= centerMel -> (mel - leftMel) / (centerMel - leftMel)
                    else -> (rightMel - mel) / (rightMel - centerMel)
                }
            }
        }
    }

    private fun fft(real: DoubleArray, imaginary: DoubleArray) {
        val size = real.size
        var reversed = 0
        for (index in 1 until size) {
            var bit = size shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit
            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue
                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        var blockSize = 2
        while (blockSize <= size) {
            val angle = -2.0 * PI / blockSize
            val stepReal = cos(angle)
            val stepImaginary = sin(angle)
            val half = blockSize / 2
            var blockStart = 0
            while (blockStart < size) {
                var weightReal = 1.0
                var weightImaginary = 0.0
                for (offset in 0 until half) {
                    val even = blockStart + offset
                    val odd = even + half
                    val oddReal = real[odd] * weightReal - imaginary[odd] * weightImaginary
                    val oddImaginary = real[odd] * weightImaginary + imaginary[odd] * weightReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImaginary
                    real[even] += oddReal
                    imaginary[even] += oddImaginary
                    val nextReal = weightReal * stepReal - weightImaginary * stepImaginary
                    weightImaginary = weightReal * stepImaginary + weightImaginary * stepReal
                    weightReal = nextReal
                }
                blockStart += blockSize
            }
            blockSize = blockSize shl 1
        }
    }

    private fun hzToMel(hz: Double): Double = 1_127.0 * ln(1.0 + hz / 700.0)

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
