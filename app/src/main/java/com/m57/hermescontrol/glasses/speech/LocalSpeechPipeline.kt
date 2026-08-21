package com.m57.hermescontrol.glasses.speech

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Bridges the AudioRecord callback to native work without network or chat dependencies. */
internal class LocalSpeechPipeline(
    private val engine: SpeechEngine,
    private val onUtterance: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val segmenter: WhisperVadSegmenter = WhisperVadSegmenter(frameMillis = VAD_WINDOW_MILLIS),
) : Closeable {
    private data class Frame(val bytes: ByteArray = ByteArray(FRAME_BYTES), var size: Int = 0)

    private val lock = Any()
    private val available = ArrayDeque<Frame>(CAPACITY)
    private val pending = ArrayDeque<Frame>(CAPACITY)
    private val active = AtomicBoolean(true)
    private val vadWindow = ByteArray(VAD_WINDOW_BYTES)
    private val carry = ByteArray(FRAME_BYTES)
    private var vadWindowSize = 0
    private var carrySize = 0
    private val worker: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "myvu-speech-pipeline").apply { isDaemon = true }
        }
    private val draining = AtomicBoolean(false)
    private var closed = false

    init {
        repeat(CAPACITY) { available.addLast(Frame()) }
    }

    /** Returns immediately; a full queue becomes one terminal failure, never silent audio loss. */
    fun offer(
        pcm: ByteArray,
        size: Int,
    ): Boolean {
        if (size !in 1..FRAME_BYTES || !active.get()) return false
        val overflow =
            synchronized(lock) {
                if (closed || !active.get()) return false
                val frame = if (available.isEmpty()) null else available.removeFirst()
                if (frame == null) {
                    true
                } else {
                    pcm.copyInto(frame.bytes, endIndex = size)
                    frame.size = size
                    pending.addLast(frame)
                    if (draining.compareAndSet(false, true)) worker.execute(::drain)
                    return true
                }
            }
        if (overflow) terminalFailure(OverflowException())
        return false
    }

    fun cancel() {
        active.set(false)
        engine.cancel()
        synchronized(lock) {
            pending.clearTo(available)
            segmenter.reset()
        }
    }

    fun stopInput() {
        active.set(false)
        synchronized(lock) {
            pending.clearTo(available)
            segmenter.reset()
        }
        worker.shutdown()
    }

    override fun close() {
        active.set(false)
        synchronized(lock) {
            if (closed) return
            closed = true
            pending.clearTo(available)
            segmenter.reset()
        }
        engine.cancel()
        worker.shutdown()
    }

    private fun drain() {
        while (active.get()) {
            if (!fillVadWindow()) return
            engine.vad(vadWindow) { result ->
                if (!active.get()) return@vad
                result
                    .onSuccess { vad ->
                        if (!vad.processed) {
                            terminalFailure(IllegalStateException("Native VAD processing failed"))
                            return@onSuccess
                        }
                        when (val event = segmenter.accept(vadWindow, vad.probability)) {
                            is WhisperVadSegmenter.Event.Utterance -> {
                                stopAfterUtterance()
                                onUtterance(event.pcm)
                            }
                            WhisperVadSegmenter.Event.Discarded,
                            null,
                            -> finishVadWindow()
                        }
                    }.onFailure(::terminalFailure)
            }
            return
        }
        draining.set(false)
    }

    private fun fillVadWindow(): Boolean {
        synchronized(lock) {
            if (carrySize > 0) {
                carry.copyInto(vadWindow, vadWindowSize, 0, carrySize)
                vadWindowSize += carrySize
                carrySize = 0
            }
            while (vadWindowSize < VAD_WINDOW_BYTES && pending.isNotEmpty()) {
                val frame = pending.removeFirst()
                val bytesForWindow = minOf(frame.size, VAD_WINDOW_BYTES - vadWindowSize)
                frame.bytes.copyInto(vadWindow, vadWindowSize, 0, bytesForWindow)
                vadWindowSize += bytesForWindow
                if (bytesForWindow < frame.size) {
                    val remaining = frame.size - bytesForWindow
                    frame.bytes.copyInto(carry, 0, bytesForWindow, frame.size)
                    carrySize = remaining
                }
                available.addLast(frame)
            }
            if (vadWindowSize == VAD_WINDOW_BYTES) return true
            draining.set(false)
            return false
        }
    }

    private fun finishVadWindow() {
        vadWindowSize = 0
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!active.get() || !draining.get()) return
        try {
            worker.execute(::drain)
        } catch (_: RejectedExecutionException) {
            // A concurrent stop owns the terminal state.
        }
    }

    private fun stopAfterUtterance() {
        active.set(false)
        synchronized(lock) {
            pending.clearTo(available)
            vadWindowSize = 0
            carrySize = 0
            draining.set(false)
        }
        worker.shutdown()
    }

    private fun terminalFailure(error: Throwable) {
        val notify =
            synchronized(lock) {
                if (closed || !active.getAndSet(false)) {
                    false
                } else {
                    pending.clearTo(available)
                    segmenter.reset()
                    vadWindowSize = 0
                    carrySize = 0
                    draining.set(false)
                    true
                }
            }
        if (notify) {
            engine.cancel()
            worker.shutdown()
            onFailure(error)
        }
    }

    private fun ArrayDeque<Frame>.clearTo(destination: ArrayDeque<Frame>) {
        while (isNotEmpty()) destination.addLast(removeFirst())
    }

    internal class OverflowException : IllegalStateException("Speech pipeline queue is full")

    private companion object {
        const val CAPACITY = 25
        const val FRAME_BYTES = 640
        const val VAD_WINDOW_BYTES = 1_024
        const val VAD_WINDOW_MILLIS = 32
    }
}
