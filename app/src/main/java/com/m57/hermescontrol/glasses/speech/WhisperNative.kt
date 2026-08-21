package com.m57.hermescontrol.glasses.speech

/** Narrow JNI boundary. Native handles are owned only by [WhisperEngine]'s executor. */
internal interface WhisperNativeBridge {
    fun version(): String

    fun open(
        whisperModelPath: String,
        vadModelPath: String,
        threads: Int,
    ): Long

    fun close(handle: Long)

    fun cancel(handle: Long)

    fun resetVad(handle: Long)

    fun vadProbability(
        handle: Long,
        samples: FloatArray,
    ): WhisperNative.VadResult

    fun transcribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
    ): String
}

internal object WhisperNative : WhisperNativeBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    data class VadResult(
        val processed: Boolean,
        val probability: Float,
    )

    override fun version(): String = nativeVersion()

    override fun open(
        whisperModelPath: String,
        vadModelPath: String,
        threads: Int,
    ): Long = nativeOpen(whisperModelPath, vadModelPath, threads)

    override fun close(handle: Long) = nativeClose(handle)

    override fun cancel(handle: Long) = nativeCancel(handle)

    override fun resetVad(handle: Long) = nativeResetVad(handle)

    override fun vadProbability(
        handle: Long,
        samples: FloatArray,
    ): VadResult {
        val values = nativeVadProbability(handle, samples)
        check(values.size == 2) { "Native VAD returned malformed result" }
        // The first value reports whether inference completed. It is deliberately
        // not a speech decision; endpointing owns the copied probability below.
        return VadResult(processed = values[0] == 1f, probability = values[1])
    }

    override fun transcribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
    ): String = nativeTranscribe(handle, samples, threads)

    private external fun nativeVersion(): String

    private external fun nativeOpen(
        whisperModelPath: String,
        vadModelPath: String,
        threads: Int,
    ): Long

    private external fun nativeClose(handle: Long)

    private external fun nativeCancel(handle: Long)

    private external fun nativeResetVad(handle: Long)

    private external fun nativeVadProbability(
        handle: Long,
        samples: FloatArray,
    ): FloatArray

    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
    ): String
}
