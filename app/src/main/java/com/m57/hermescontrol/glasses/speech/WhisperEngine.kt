package com.m57.hermescontrol.glasses.speech

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal interface SpeechEngine : Closeable {
    fun vad(
        pcm: ByteArray,
        onResult: (Result<WhisperNative.VadResult>) -> Unit,
    )

    fun transcribe(
        pcm: ByteArray,
        onResult: (Result<String>) -> Unit,
    )

    fun resetVad(onResult: (Result<Unit>) -> Unit = {})

    fun cancel()
}

/** A session-native owner: contexts stay on one executor; cancellation is the only concurrent call. */
internal class WhisperEngine(
    private val threads: Int = 3,
    private val native: WhisperNativeBridge = WhisperNative,
) : SpeechEngine {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "myvu-whisper").apply { isDaemon = true }
        }
    private val handle = AtomicReference<Long?>(null)
    private val cancellationEpoch = AtomicLong(0)
    private val closed = AtomicBoolean(false)
    private val vadSamples = FloatArray(VAD_WINDOW_SAMPLES)

    fun open(
        models: WhisperModelStore.ReadyModels,
        onResult: (Result<Unit>) -> Unit,
    ) {
        submit {
            val result =
                runCatching {
                    check(handle.get() == null) { "Whisper engine is already open" }
                    handle.set(native.open(models.whisperPath, models.vadPath, threads))
                }
            if (!closed.get()) onResult(result)
        }
    }

    override fun vad(
        pcm: ByteArray,
        onResult: (Result<WhisperNative.VadResult>) -> Unit,
    ) {
        val requestedEpoch = cancellationEpoch.get()
        submit {
            val result =
                runCatching {
                    check(requestedEpoch == cancellationEpoch.get()) { "Whisper work cancelled" }
                    native.vadProbability(checkNotNull(handle.get()), pcm.toFloatPcm(vadSamples))
                        .also {
                            check(requestedEpoch == cancellationEpoch.get()) { "Whisper work cancelled" }
                        }
                }
            if (!closed.get()) onResult(result)
        }
    }

    override fun transcribe(
        pcm: ByteArray,
        onResult: (Result<String>) -> Unit,
    ) {
        val requestedEpoch = cancellationEpoch.get()
        submit {
            val result =
                runCatching {
                    check(requestedEpoch == cancellationEpoch.get()) { "Whisper work cancelled" }
                    native.transcribe(checkNotNull(handle.get()), pcm.toFloatPcm(), threads)
                        .trim()
                        .also {
                            check(requestedEpoch == cancellationEpoch.get()) { "Whisper work cancelled" }
                        }
                }
            if (!closed.get()) onResult(result)
        }
    }

    override fun resetVad(onResult: (Result<Unit>) -> Unit) {
        submit {
            val result = runCatching { native.resetVad(checkNotNull(handle.get())) }
            if (!closed.get()) onResult(result)
        }
    }

    /** Safe from AudioRecord, UI, and controller threads: JNI only sets an atomic native flag. */
    override fun cancel() {
        cancellationEpoch.incrementAndGet()
        handle.get()?.let(native::cancel)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        executor.execute {
            handle.getAndSet(null)?.let(native::close)
            executor.shutdown()
        }
    }

    private fun submit(task: () -> Unit) {
        if (closed.get()) return
        try {
            executor.execute {
                if (!closed.get()) task()
            }
        } catch (_: RejectedExecutionException) {
            // A concurrent close owns the terminal state and suppresses callbacks.
        }
    }

    private fun ByteArray.toFloatPcm(reuse: FloatArray? = null): FloatArray {
        require(size % 2 == 0) { "PCM16 data must be sample aligned" }
        val sampleCount = size / 2
        val output = if (reuse?.size == sampleCount) reuse else FloatArray(sampleCount)
        repeat(sampleCount) { index ->
            val offset = index * 2
            val sample = ((this[offset + 1].toInt() shl 8) or (this[offset].toInt() and 0xff)).toShort()
            output[index] = sample / 32768f
        }
        return output
    }

    private companion object {
        const val VAD_WINDOW_SAMPLES = 512
    }
}
