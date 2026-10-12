package dev.retrobit.voice

import dev.retrobit.voice.text.LocalTextCleaner
import dev.retrobit.voice.text.NumberWords
import dev.retrobit.voice.text.Tokenizer
import dev.retrobit.voice.audio.VoiceActivityDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uji pembersih teks lokal — bagian yang paling sering dipakai pengguna. */
class LocalTextCleanerTest {

    @Test
    fun `membuang kata pengisi`() {
        val raw = "eee jadi gini hmm saya mau pesan tiket"
        val cleaned = LocalTextCleaner.clean(raw)
        assertFalse(cleaned.contains("eee"))
        assertFalse(cleaned.contains("hmm"))
        assertTrue(cleaned.contains("saya mau pesan tiket"))
    }

    @Test
    fun `mengubah perintah tanda baca yang diucapkan`() {
        val raw = "tolong kirim laporan hari ini titik terima kasih koma ya"
        val cleaned = LocalTextCleaner.clean(raw)
        assertTrue("hasil: $cleaned", cleaned.contains("."))
        assertTrue("hasil: $cleaned", cleaned.contains(","))
    }

    @Test
    fun `menambahkan titik dan huruf besar`() {
        val cleaned = LocalTextCleaner.clean("besok kita rapat jam sembilan")
        assertTrue("hasil: $cleaned", cleaned.startsWith("B"))
        assertTrue("hasil: $cleaned", cleaned.endsWith("."))
    }

    @Test
    fun `tidak merusak penulisan jam`() {
        val cleaned = LocalTextCleaner.clean("rapat pukul 7:30 ya")
        assertTrue("hasil: $cleaned", cleaned.contains("7:30"))
        assertFalse("hasil: $cleaned", cleaned.contains("7: 30"))
    }

    @Test
    fun `menghormati kamus pengguna`() {
        val cleaned = LocalTextCleaner.clean(
            "buka retro bit dulu",
            LocalTextCleaner.Options(replacements = mapOf("retro bit" to "Retrobit")),
        )
        assertTrue("hasil: $cleaned", cleaned.contains("Retrobit"))
    }

    @Test
    fun `teks kosong tetap kosong`() {
        assertEquals("", LocalTextCleaner.clean("   "))
    }
}

/** Uji pembacaan angka terucap (Indonesia & Inggris). */
class NumberWordsTest {

    private fun find(text: String) = NumberWords.findIn(text)

    @Test
    fun `numerik langsung`() {
        assertEquals(7, find("jam 7 pagi"))
        assertEquals(30, find("timer 30 detik"))
    }

    @Test
    fun `angka Indonesia`() {
        assertEquals(7, find("jam tujuh pagi"))
        assertEquals(12, find("jam dua belas"))
        assertEquals(25, find("dua puluh lima menit"))
        assertEquals(11, find("sebelas"))
    }

    @Test
    fun `angka Inggris`() {
        assertEquals(7, find("seven minutes"))
        assertEquals(45, find("forty five seconds"))
    }

    @Test
    fun `tidak menemukan angka`() {
        assertEquals(null, find("buka whatsapp sekarang"))
    }

    @Test
    fun `tokenizer menormalkan tanda baca`() {
        val tokens = Tokenizer.normalize("Buka WhatsApp, tolong!")
        assertEquals(listOf("buka", "whatsapp", "tolong"), tokens)
    }
}

/** Uji detektor hening (mengendalikan auto-stop saat dikte). */
class VoiceActivityDetectorTest {

    @Test
    fun `ucapan terdeteksi setelah ambang dan durasi`() {
        val vad = VoiceActivityDetector()
        var t = 0L
        repeat(20) {
            vad.process(0.002f, 30, t)   // ruangan sepi → menetapkan noise floor
            t += 30
        }
        repeat(10) {
            vad.process(0.4f, 30, t)     // bicara keras
            t += 30
        }
        assertTrue(vad.hasSpeech)
        assertTrue(vad.silenceDurationMs(t) < 100)
    }

    @Test
    fun `keheningan menghitung sejak ucapan terakhir`() {
        val vad = VoiceActivityDetector()
        var t = 0L
        repeat(10) {
            vad.process(0.3f, 30, t)
            t += 30
        }
        t += 2000
        vad.process(0.001f, 30, t)
        assertTrue(vad.silenceDurationMs(t) >= 2000)
    }

    @Test
    fun `tanpa ucapan tidak dianggap bicara`() {
        val vad = VoiceActivityDetector()
        var t = 0L
        repeat(50) {
            vad.process(0.001f, 30, t)
            t += 30
        }
        assertFalse(vad.hasSpeech)
    }
}
