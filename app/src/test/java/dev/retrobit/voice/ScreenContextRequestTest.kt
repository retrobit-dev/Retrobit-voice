package dev.retrobit.voice

import dev.retrobit.voice.assistant.ScreenContextRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContextRequestTest {

    @Test
    fun `detects Indonesian and English requests to use visible chat text`() {
        listOf(
            "Balas pesan terbaru dari Adi",
            "Tolong jawab chat ini",
            "Baca pesan ini",
            "Reply to this message",
            "What did they say?",
        ).forEach { assertTrue(it, ScreenContextRequest.isRequested(it)) }
    }

    @Test
    fun `detects explicit request to read visible text`() {
        listOf("Tolong bacakan pesan WhatsApp", "Baca layar ini", "Read the screen")
            .forEach {
                assertTrue(it, ScreenContextRequest.isRequested(it))
                assertTrue(it, ScreenContextRequest.isReadRequested(it))
            }
        assertFalse(ScreenContextRequest.isReadRequested("Balas pesan terbaru"))
    }

    @Test
    fun `captures media context for short playback commands`() {
        listOf("Mainkan", "Jeda", "Lagu berikutnya", "Play", "Pause")
            .forEach { assertTrue(it, ScreenContextRequest.isRequested(it)) }
    }

    @Test
    fun `does not capture screen for unrelated assistant commands`() {
        listOf(
            "Pasang alarm jam tujuh",
            "Nyalakan senter",
            "Kirim pesan ke Adi isinya aku otw",
            "Open display settings",
            "I want to play football",
            "Chat WhatsApp dengan Budi",
        )
            .forEach { assertFalse(it, ScreenContextRequest.isRequested(it)) }
    }
}
