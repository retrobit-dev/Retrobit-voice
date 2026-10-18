package dev.retrobit.voice.core

import ai.onnxruntime.OrtSession

/**
 * Conservative CPU settings for Android inference after native crashes on mobile devices.
 * Keep execution sequential, avoid graph/memory-pattern optimizations, and bypass KleidiAI
 * micro-kernels so ONNX Runtime falls back to its generic CPU kernels where available.
 */
internal fun OrtSession.SessionOptions.useConservativeCpuMode() {
    setIntraOpNumThreads(1)
    setInterOpNumThreads(1)
    setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
    setMemoryPatternOptimization(false)
    setCPUArenaAllocator(false)
    addConfigEntry("mlas.disable_kleidiai", "1")
}
