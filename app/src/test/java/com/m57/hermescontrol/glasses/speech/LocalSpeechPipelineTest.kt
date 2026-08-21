package com.m57.hermescontrol.glasses.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalSpeechPipelineTest {
    @Test
    fun pooled_frames_keep_offer_order_and_report_overflow_once() {
        val engine = RecordingEngine()
        val failures = mutableListOf<Throwable>()
        val pipeline = LocalSpeechPipeline(engine, onUtterance = {}, onFailure = failures::add)

        assertTrue(pipeline.offer(ByteArray(640), 640))
        assertTrue(pipeline.offer(ByteArray(640), 640))
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        repeat(25) { assertTrue(pipeline.offer(ByteArray(640) { it.toByte() }, 640)) }
        assertFalse(pipeline.offer(ByteArray(640), 640))
        assertEquals(1, failures.filterIsInstance<LocalSpeechPipeline.OverflowException>().size)
        engine.completeAll()
        assertFalse(pipeline.offer(ByteArray(640), 640))
        pipeline.close()
    }

    @Test
    fun overflow_failure_callback_runs_after_releasing_the_capture_lock() {
        val engine = RecordingEngine()
        val competingOfferFinished = CountDownLatch(1)
        lateinit var pipeline: LocalSpeechPipeline
        pipeline =
            LocalSpeechPipeline(
                engine,
                onUtterance = {},
                onFailure = {
                    Thread {
                        pipeline.offer(ByteArray(640), 640)
                        competingOfferFinished.countDown()
                    }.start()
                    assertTrue(competingOfferFinished.await(2, TimeUnit.SECONDS))
                },
            )

        assertTrue(pipeline.offer(ByteArray(640), 640))
        assertTrue(pipeline.offer(ByteArray(640), 640))
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        repeat(25) { assertTrue(pipeline.offer(ByteArray(640), 640)) }

        assertFalse(pipeline.offer(ByteArray(640), 640))
        pipeline.close()
    }

    @Test
    fun vad_receives_complete_512_sample_windows_from_20ms_capture_frames() {
        val engine = RecordingEngine()
        val pipeline = LocalSpeechPipeline(engine, onUtterance = {}, onFailure = {})

        assertTrue(pipeline.offer(ByteArray(640) { 1 }, 640))
        assertTrue(pipeline.offer(ByteArray(640) { 2 }, 640))

        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        assertEquals(1_024, engine.firstVadInput().size)
        assertEquals(1, engine.firstVadInput()[639].toInt())
        assertEquals(2, engine.firstVadInput()[640].toInt())
        pipeline.close()
    }

    @Test
    fun stop_suppresses_late_callbacks_and_rejects_new_frames() {
        val engine = RecordingEngine()
        val utterances = mutableListOf<ByteArray>()
        val pipeline = LocalSpeechPipeline(engine, utterances::add, onFailure = {})

        assertTrue(pipeline.offer(ByteArray(640) { 1 }, 640))
        assertTrue(pipeline.offer(ByteArray(640) { 2 }, 640))
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        pipeline.stopInput()
        engine.completeAll()

        assertFalse(pipeline.offer(ByteArray(640) { 2 }, 640))
        assertTrue(utterances.isEmpty())
        pipeline.close()
    }

    private class RecordingEngine : SpeechEngine {
        private val callbacks = mutableListOf<(Result<WhisperNative.VadResult>) -> Unit>()
        private val inputs = mutableListOf<ByteArray>()
        val started = CountDownLatch(1)

        override fun vad(
            pcm: ByteArray,
            onResult: (Result<WhisperNative.VadResult>) -> Unit,
        ) {
            synchronized(callbacks) {
                inputs += pcm.copyOf()
                callbacks += onResult
            }
            started.countDown()
        }

        fun firstVadInput(): ByteArray = synchronized(callbacks) { checkNotNull(inputs.firstOrNull()) }

        fun completeAll() =
            synchronized(callbacks) {
                callbacks.forEach { it(Result.success(WhisperNative.VadResult(processed = true, probability = 0f))) }
                callbacks.clear()
            }

        override fun transcribe(
            pcm: ByteArray,
            onResult: (Result<String>) -> Unit,
        ) = Unit

        override fun resetVad(onResult: (Result<Unit>) -> Unit) = onResult(Result.success(Unit))

        override fun cancel() = Unit

        override fun close() = Unit
    }
}
