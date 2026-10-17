package dev.retrobit.voice

import dev.retrobit.voice.assistant.WakeWordAudioFeatures
import dev.retrobit.voice.assistant.WakeWordEmbeddingSequence
import dev.retrobit.voice.assistant.WakeWordProfileCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

class WakeWordAudioFeaturesTest {
    @Test
    fun `phrase normalization ignores punctuation spacing and unicode compatibility`() {
        assertEquals("hai retro", WakeWordAudioFeatures.normalizePhrase("  HAI—Retro!!  "))
        assertEquals("12 halo", WakeWordAudioFeatures.normalizePhrase("１２   HALO"))
    }

    @Test
    fun `trim speech preserves voice and pads short utterances to model minimum`() {
        val samples = FloatArray(WakeWordAudioFeatures.SAMPLE_RATE_HZ * 2) { index ->
            val seconds = index.toDouble() / WakeWordAudioFeatures.SAMPLE_RATE_HZ
            if (seconds in 0.55..0.75) (0.08 * sin(2.0 * PI * 220 * seconds)).toFloat() else 0f
        }

        val trimmed = WakeWordAudioFeatures.trimSpeech(samples)

        assertEquals(WakeWordAudioFeatures.MIN_MODEL_SAMPLES, trimmed.size)
        assertTrue(trimmed.any { kotlin.math.abs(it) > 0.01f })
        assertTrue(trimmed.first() == 0f)
        assertTrue(trimmed.last() == 0f)
    }

    @Test
    fun `cosine dtw reports zero distance for identical normalized sequences`() {
        val sequence = oneHotSequence(4)

        assertEquals(0f, WakeWordAudioFeatures.dtwCosineNormalizedDistance(sequence, sequence), 1e-6f)
    }

    @Test
    fun `profile codec round trips exactly three embedding templates`() {
        val templates = listOf(oneHotSequence(2, 0), oneHotSequence(3, 4), oneHotSequence(2, 8))

        val restored = WakeWordProfileCodec.decode(WakeWordProfileCodec.encode(" HAI, Retro! ", templates))

        assertEquals("hai retro", restored.phrase)
        assertEquals(3, restored.templates.size)
        templates.zip(restored.templates).forEach { (expected, actual) ->
            assertEquals(expected.frameCount, actual.frameCount)
            assertArrayEquals(expected.values, actual.values, 0f)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile codec rejects a template count other than three`() {
        WakeWordProfileCodec.encode("hai retro", listOf(oneHotSequence(2)))
    }

    @Test
    fun `wav reader accepts the recorder pcm format`() {
        val sampleCount = WakeWordAudioFeatures.MIN_MODEL_SAMPLES
        val wav = pcm16MonoWav(sampleCount) { index -> if (index % 2 == 0) 4_000 else -4_000 }
        val file = File.createTempFile("wake-word-test", ".wav")
        try {
            file.writeBytes(wav)

            val samples = WakeWordAudioFeatures.readPcm16MonoWav(file)

            assertEquals(sampleCount, samples.size)
            assertEquals(4_000 / 32768f, samples[0], 1e-6f)
            assertEquals(-4_000 / 32768f, samples[1], 1e-6f)
        } finally {
            file.delete()
        }
    }

    private fun oneHotSequence(frameCount: Int, offset: Int = 0): WakeWordEmbeddingSequence {
        val values = FloatArray(frameCount * WakeWordAudioFeatures.EMBEDDING_DIMENSION)
        repeat(frameCount) { frame ->
            values[frame * WakeWordAudioFeatures.EMBEDDING_DIMENSION + (frame + offset) % WakeWordAudioFeatures.EMBEDDING_DIMENSION] = 1f
        }
        return WakeWordEmbeddingSequence(frameCount, values)
    }

    private fun pcm16MonoWav(sampleCount: Int, sample: (Int) -> Int): ByteArray {
        val dataSize = sampleCount * 2
        val bytes = ByteArrayOutputStream(44 + dataSize)
        DataOutputStream(bytes).use { output ->
            output.writeBytes("RIFF")
            output.writeLittleEndianInt(36 + dataSize)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            output.writeLittleEndianInt(16)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianShort(1)
            output.writeLittleEndianInt(WakeWordAudioFeatures.SAMPLE_RATE_HZ)
            output.writeLittleEndianInt(WakeWordAudioFeatures.SAMPLE_RATE_HZ * 2)
            output.writeLittleEndianShort(2)
            output.writeLittleEndianShort(16)
            output.writeBytes("data")
            output.writeLittleEndianInt(dataSize)
            repeat(sampleCount) { output.writeLittleEndianShort(sample(it)) }
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeLittleEndianInt(value: Int) {
        writeByte(value and 0xff)
        writeByte(value ushr 8 and 0xff)
        writeByte(value ushr 16 and 0xff)
        writeByte(value ushr 24 and 0xff)
    }

    private fun DataOutputStream.writeLittleEndianShort(value: Int) {
        writeByte(value and 0xff)
        writeByte(value ushr 8 and 0xff)
    }
}
