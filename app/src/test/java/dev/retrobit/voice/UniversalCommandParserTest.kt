package dev.retrobit.voice

import dev.retrobit.voice.assistant.UniversalCommandParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UniversalCommandParserTest {

    @Test
    fun `cancel phrases are universal but only cancel pending work`() {
        listOf("batal", "Batalkan!", "batalin", "tidak jadi", "cancel it").forEach { phrase ->
            assertEquals(phrase, UniversalCommandParser.Command.CANCEL_PENDING, UniversalCommandParser.parse(phrase))
        }
    }

    @Test
    fun `repeat phrases request the last answer rather than an action`() {
        listOf("ulangi", "ulang jawaban", "ulangi jawaban terakhir", "repeat that").forEach { phrase ->
            assertEquals(phrase, UniversalCommandParser.Command.REPEAT_LAST_REPLY, UniversalCommandParser.parse(phrase))
        }
    }

    @Test
    fun `stop remains available to contextual media controls`() {
        assertNull(UniversalCommandParser.parse("stop"))
        assertNull(UniversalCommandParser.parse("berhenti"))
    }

    @Test
    fun `unrelated utterances are not swallowed as universal commands`() {
        assertNull(UniversalCommandParser.parse("telepon Ibu"))
        assertNull(UniversalCommandParser.parse("ulangi pesan ke Budi"))
    }
}
