package dev.retrobit.voice

import dev.retrobit.voice.speaker.SpeakerAudioFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpeakerAudioFeaturesTest {
    @Test
    fun `trim finds voiced span and fbank returns finite mean normalized features`() {
        val sampleRate = SpeakerAudioFeatures.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate * 3) { index ->
            val time = index.toDouble() / sampleRate
            if (time in 0.3..2.7) (0.04 * sin(2.0 * PI * 190.0 * time)).toFloat() else 0f
        }

        val trimmed = SpeakerAudioFeatures.trimSpeech(samples)
        val features = SpeakerAudioFeatures.computeFbank(trimmed)

        assertTrue(trimmed.size in (sampleRate * 2)..(sampleRate * 3))
        assertEquals(0, features.size % SpeakerAudioFeatures.FEATURE_BINS)
        assertTrue(features.isNotEmpty())
        assertTrue(features.all { it.isFinite() })
        for (bin in 0 until SpeakerAudioFeatures.FEATURE_BINS) {
            val mean = features.indices.step(SpeakerAudioFeatures.FEATURE_BINS)
                .map { features[it + bin].toDouble() }
                .average()
            assertTrue("bin $bin CMN mean=$mean", kotlin.math.abs(mean) < 1e-4)
        }
    }

    @Test
    fun `fbank matches the pinned Kaldi reference frontend`() {
        val sampleRate = SpeakerAudioFeatures.SAMPLE_RATE_HZ
        val samples = FloatArray(sampleRate) { index ->
            val time = index.toDouble() / sampleRate
            if (time in 0.1..0.9) (0.04 * sin(2.0 * PI * 190.0 * time)).toFloat() else 0f
        }

        val features = SpeakerAudioFeatures.computeFbank(samples)

        assertEquals(98 * SpeakerAudioFeatures.FEATURE_BINS, features.size)
        // Golden values generated with kaldi-native-fbank FbankOptions used by the
        // upstream JNI reference: 16 kHz, 25/10 ms, 80 bins, Hamming, no dither, CMN.
        assertEquals(-21.4318f, features[0], 0.05f)
        assertEquals(-17.5483f, features[79], 0.05f)
        assertEquals(8.0870f, features[799], 0.05f)
    }

    @Test
    fun `speaker vector normalization average and cosine are stable`() {
        val first = SpeakerAudioFeatures.normalize(floatArrayOf(3f, 4f))
        val second = SpeakerAudioFeatures.normalize(floatArrayOf(6f, 8f))
        assertNotNull(first)
        assertNotNull(second)

        val average = SpeakerAudioFeatures.averageNormalized(listOf(first!!, second!!))
        assertNotNull(average)
        assertEquals(1f, SpeakerAudioFeatures.cosineSimilarity(first, average!!)!!, 1e-5f)
        assertTrue(SpeakerAudioFeatures.cosineSimilarity(first, floatArrayOf(0f, 0f)) == null)
    }

    @Test(expected = SpeakerAudioFeatures.InvalidAudioException::class)
    fun `rejects speech shorter than minimum duration`() {
        SpeakerAudioFeatures.trimSpeech(FloatArray(SpeakerAudioFeatures.MIN_SPEECH_SAMPLES - 1))
    }
}
