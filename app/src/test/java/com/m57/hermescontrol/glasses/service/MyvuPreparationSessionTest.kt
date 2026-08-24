package com.m57.hermescontrol.glasses.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuPreparationSessionTest {
    @Test
    fun cancellation_then_immediate_restart_rejects_old_preparation_effects() {
        val sessions = MyvuPreparationSessionGate()
        val oldJob = Job()
        val old =
            sessions.start(
                job = oldJob,
                generation = 1,
                storedSessionId = "stored-1",
                runtimeSessionId = "runtime-1",
                transport = Any(),
            )
        val replacementJob = Job()
        val replacement =
            sessions.start(
                job = replacementJob,
                generation = 2,
                storedSessionId = "stored-2",
                runtimeSessionId = "runtime-2",
                transport = Any(),
            )
        val effects = mutableListOf<Long>()

        oldJob.cancel()
        assertFalse(sessions.isCurrent(old))
        sessions.ifCurrent(old) { effects += old.generation }
        assertTrue(sessions.isCurrent(replacement))
        sessions.ifCurrent(replacement) { effects += replacement.generation }

        assertEquals(listOf(2L), effects)
    }

    @Test
    fun startup_waits_for_warm_up_before_rendering_session_loaded_and_starting_capture() =
        runTest {
            val warmUpCallback = CompletableDeferred<(Result<Unit>) -> Unit>()
            val effects = mutableListOf<String>()
            val startup =
                launch {
                    awaitSpeechStartup(
                        warmUpVad = warmUpCallback::complete,
                        onSessionLoaded = {
                            effects += "Session loaded"
                            true
                        },
                        onStartCapture = { effects += "capture" },
                        onAbort = {},
                    )
                }

            runCurrent()
            assertTrue(effects.isEmpty())
            warmUpCallback.await()(Result.success(Unit))
            runCurrent()

            assertEquals(listOf("Session loaded", "capture"), effects)
            startup.join()
        }

    @Test
    fun startup_failure_does_not_render_or_start_capture() =
        runTest {
            val effects = mutableListOf<String>()
            var failure: Throwable? = null

            try {
                awaitSpeechStartup(
                    warmUpVad = { it(Result.failure(IllegalStateException("VAD did not process"))) },
                    onSessionLoaded = {
                        effects += "Session loaded"
                        true
                    },
                    onStartCapture = { effects += "capture" },
                    onAbort = { effects += "abort" },
                )
            } catch (error: Throwable) {
                failure = error
            }

            assertTrue(failure is IllegalStateException)
            assertEquals(listOf("abort"), effects)
        }

    @Test
    fun startup_rejection_closes_the_open_engine_without_starting_capture() =
        runTest {
            val effects = mutableListOf<String>()

            awaitSpeechStartup(
                warmUpVad = { it(Result.success(Unit)) },
                onSessionLoaded = { false },
                onStartCapture = { effects += "capture" },
                onAbort = { effects += "abort" },
            )

            assertEquals(listOf("abort"), effects)
        }

    @Test
    fun cancellation_closes_the_open_engine_while_warm_up_is_pending() =
        runTest {
            val warmUpCallback = CompletableDeferred<(Result<Unit>) -> Unit>()
            var aborts = 0
            val startup =
                launch {
                    awaitSpeechStartup(
                        warmUpVad = warmUpCallback::complete,
                        onSessionLoaded = { true },
                        onStartCapture = {},
                        onAbort = { aborts += 1 },
                    )
                }

            runCurrent()
            startup.cancel()
            runCurrent()
            startup.join()

            assertEquals(1, aborts)
        }
}
