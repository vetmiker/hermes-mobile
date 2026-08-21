package com.m57.hermescontrol.glasses.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WhisperEngineTest {
    @Test
    fun cancellation_fences_queued_work_and_preserves_probability_separately_from_status() {
        val native = RecordingNative()
        val engine = WhisperEngine(native = native)
        val opened = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var result: Result<WhisperNative.VadResult>? = null

        engine.open(WhisperModelStore.ReadyModels("whisper", "vad")) { opened.countDown() }
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        engine.cancel()
        engine.vad(byteArrayOf(0, 0)) {
            result = it
            completed.countDown()
        }

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(result?.isFailure == true)
        assertEquals(0, native.vadCalls)
        engine.close()
    }

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

    private class RecordingNative : WhisperNativeBridge {
        var vadCalls = 0

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
            vadCalls += 1
            return WhisperNative.VadResult(processed = false, probability = .75f)
        }

        override fun transcribe(
            handle: Long,
            samples: FloatArray,
            threads: Int,
        ) = ""
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
}
