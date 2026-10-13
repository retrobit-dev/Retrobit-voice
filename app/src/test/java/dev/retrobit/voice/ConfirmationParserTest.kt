package dev.retrobit.voice

import dev.retrobit.voice.assistant.ConfirmationParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfirmationParserTest {

    @Test
    fun `recognized explicit Indonesian and English approvals`() {
        listOf("ya", "iya, kirim", "oke lanjutkan", "ya, siapkan draf", "buka draf", "send").forEach { phrase ->
            assertEquals(phrase, ConfirmationParser.Decision.YES, ConfirmationParser.parse(phrase))
        }
    }

    @Test
    fun `recognized cancellations and mixed no phrases never approve`() {
        listOf("tidak", "batal kirim", "jangan kirim", "ya tapi jangan kirim").forEach { phrase ->
            assertEquals(phrase, ConfirmationParser.Decision.NO, ConfirmationParser.parse(phrase))
        }
    }

    @Test
    fun `unrelated speech is not confirmation`() {
        assertEquals(ConfirmationParser.Decision.UNCLEAR, ConfirmationParser.parse("ubah pesannya dulu"))
    }
}
