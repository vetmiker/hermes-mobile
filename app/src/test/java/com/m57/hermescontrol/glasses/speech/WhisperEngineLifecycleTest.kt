package com.m57.hermescontrol.glasses.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WhisperEngineLifecycleTest {
    @Test
    fun close_suppresses_in_flight_callbacks_and_remains_idempotent_after_shutdown() {
        val native = BlockingNative()
        val engine = WhisperEngine(native = native)
        val opened = CountDownLatch(1)
        val callback = CountDownLatch(1)

        engine.open(WhisperModelStore.ReadyModels("whisper", "vad")) { opened.countDown() }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        engine.vad(byteArrayOf(0, 0)) { callback.countDown() }
        assertTrue(native.vadStarted.await(2, TimeUnit.SECONDS))
        engine.close()
        native.releaseVad.countDown()

        assertTrue(native.closed.await(2, TimeUnit.SECONDS))
        assertFalse(callback.await(200, TimeUnit.MILLISECONDS))
        engine.close()
    }

    @Test
    fun reuses_the_exact_window_float_buffer_for_steady_state_vad() {
        val native = ReusingNative()
        val engine = WhisperEngine(native = native)
        val opened = CountDownLatch(1)
        val completed = CountDownLatch(2)

        engine.open(WhisperModelStore.ReadyModels("whisper", "vad")) { opened.countDown() }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        repeat(2) { engine.vad(ByteArray(1_024)) { completed.countDown() } }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertSame(native.vadInputs[0], native.vadInputs[1])
        engine.close()
    }

    private class BlockingNative : WhisperNativeBridge {
        val vadStarted = CountDownLatch(1)
        val releaseVad = CountDownLatch(1)
        val closed = CountDownLatch(1)

        override fun version() = "v1.9.3"

        override fun open(
            whisperModelPath: String,
            vadModelPath: String,
            threads: Int,
        ) = 1L

        override fun close(handle: Long) = closed.countDown()

        override fun cancel(handle: Long) = Unit

        override fun resetVad(handle: Long) = Unit

        override fun vadProbability(
            handle: Long,
            samples: FloatArray,
        ): WhisperNative.VadResult {
            vadStarted.countDown()
            check(releaseVad.await(2, TimeUnit.SECONDS))
            return WhisperNative.VadResult(processed = true, probability = .75f)
        }

        override fun transcribe(
            handle: Long,
            samples: FloatArray,
            threads: Int,
        ) = ""
    }

    private class ReusingNative : WhisperNativeBridge {
        val vadInputs = mutableListOf<FloatArray>()

        override fun version() = "v1.9.3"

        override fun open(
            whisperModelPath: String,
            vadModelPath: String,
            threads: Int,
        ) = 1L

        override fun close(handle: Long) = Unit

        override fun cancel(handle: Long) = Unit

        override fun resetVad(handle: Long) = Unit

        override fun vadProbability(
            handle: Long,
            samples: FloatArray,
        ): WhisperNative.VadResult {
            vadInputs += samples
            return WhisperNative.VadResult(processed = true, probability = .75f)
        }

        override fun transcribe(
            handle: Long,
            samples: FloatArray,
            threads: Int,
        ) = ""
    }
}
