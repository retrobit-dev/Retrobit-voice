package dev.retrobit.voice

import dev.retrobit.voice.dictation.insert.TextInsertionPlan
import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionPlanTest {

    @Test
    fun `inserts at caret and retains text on both sides`() {
        val result = TextInsertionPlan.insert("helloworld", 5, 5, " ")

        assertEquals("hello world", result.text)
        assertEquals(6, result.cursor)
    }

    @Test
    fun `replaces selected text and places caret after insertion`() {
        val result = TextInsertionPlan.insert("hello old world", 6, 9, "new")

        assertEquals("hello new world", result.text)
        assertEquals(9, result.cursor)
    }

    @Test
    fun `missing selection metadata appends instead of overwriting field`() {
        val result = TextInsertionPlan.insert("existing", -1, -1, " dictated")

        assertEquals("existing dictated", result.text)
        assertEquals(result.text.length, result.cursor)
    }
}
