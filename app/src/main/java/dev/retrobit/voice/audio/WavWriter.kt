package dev.retrobit.voice.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Helper WAV (PCM 16-bit) minimalis: tulis header placeholder, tambahkan data
 * PCM, lalu perbarui ukuran header saat rekaman selesai.
 *
 * Format yang dihasilkan adalah mono 16-bit pada [sampleRate] (bawaan 16 kHz) —
 * format yang diterima oleh Google on-device recognizer, Whisper, Groq, dan Deepgram.
 */
object WavWriter {

    const val HEADER_SIZE = 44
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    fun newFile(dir: File, prefix: String = "dikte"): File {
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$prefix-${System.currentTimeMillis()}.wav")
    }

    fun writePlaceholderHeader(file: File, sampleRate: Int = SAMPLE_RATE) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header(sampleRate, 0))
        }
    }

    /** Perbarui ukuran data pada header. Panggil setelah perekaman selesai. */
    fun finalizeHeader(file: File, sampleRate: Int = SAMPLE_RATE) {
        RandomAccessFile(file, "rw").use { raf ->
            val dataSize = (raf.length() - HEADER_SIZE).coerceAtLeast(0L)
            raf.seek(0)
            raf.write(header(sampleRate, dataSize.toInt()))
        }
    }

    fun header(sampleRate: Int = SAMPLE_RATE, dataSize: Int = 0): ByteArray {
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
        val out = ByteArray(HEADER_SIZE)
        var p = 0
        fun str(s: String) { for (c in s) out[p++] = c.code.toByte() }
        fun i32(v: Int) { out[p++] = (v and 0xff).toByte(); out[p++] = (v shr 8 and 0xff).toByte(); out[p++] = (v shr 16 and 0xff).toByte(); out[p++] = (v shr 24 and 0xff).toByte() }
        fun i16(v: Int) { out[p++] = (v and 0xff).toByte(); out[p++] = (v shr 8 and 0xff).toByte() }

        str("RIFF"); i32(36 + dataSize); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(CHANNELS); i32(sampleRate); i32(byteRate); i16(blockAlign); i16(BITS_PER_SAMPLE)
        str("data"); i32(dataSize)
        return out
    }

    /** Durasi (ms) dari ukuran berkas WAV. */
    fun durationMsOf(file: File, sampleRate: Int = SAMPLE_RATE): Long {
        if (!file.exists()) return 0L
        val dataBytes = (file.length() - HEADER_SIZE).coerceAtLeast(0L)
        return dataBytes * 1000L / (sampleRate * 2L)
    }
}
