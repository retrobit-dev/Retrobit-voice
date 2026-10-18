package dev.retrobit.voice.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Create the direct, native-order float input buffer expected by ONNX Runtime's JNI path. */
internal fun FloatArray.toDirectNativeFloatBuffer(): FloatBuffer {
    val byteCount = Math.multiplyExact(size, Float.SIZE_BYTES)
    return ByteBuffer.allocateDirect(byteCount)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(this@toDirectNativeFloatBuffer)
            flip()
        }
}
