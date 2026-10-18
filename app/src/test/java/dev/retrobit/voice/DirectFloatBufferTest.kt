package dev.retrobit.voice

import dev.retrobit.voice.core.toDirectNativeFloatBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteOrder

class DirectFloatBufferTest {
    @Test
    fun `float tensor input buffer is direct native order and preserves all values`() {
        val values = floatArrayOf(-1.25f, 0f, 0.5f, 9.75f)

        val buffer = values.toDirectNativeFloatBuffer()

        assertTrue(buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), buffer.order())
        assertEquals(values.size, buffer.remaining())
        val restored = FloatArray(buffer.remaining())
        buffer.get(restored)
        assertArrayEquals(values, restored, 0f)
    }
}
