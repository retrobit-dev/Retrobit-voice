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
    fun `does not capture screen for unrelated assistant commands`() {
        listOf("Pasang alarm jam tujuh", "Nyalakan senter", "Kirim pesan ke Adi isinya aku otw")
            .forEach { assertFalse(it, ScreenContextRequest.isRequested(it)) }
    }
}
