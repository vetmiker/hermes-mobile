package com.m57.hermescontrol.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesModeControllerTest {
    @Test
    fun transcript_accepts_once_and_rejects_duplicate_or_stale_generation() {
        val controller = GlassesModeController()
        val started = startListening(controller, "stored-a", "runtime-a")
        val streamId = checkNotNull(started.activeStreamId)

        assertTrue(
            controller.acceptTranscript(started.generation, streamId, "utterance-a", "hello").accepted,
        )
        assertEquals(GlassesModeState.AWAITING_HERMES, controller.snapshot.value.state)
        assertFalse(
            controller.acceptTranscript(started.generation, streamId, "utterance-a", "hello").accepted,
        )
        assertFalse(
            controller.acceptTranscript(
                started.generation - 1,
                streamId,
                "utterance-b",
                "late",
            ).accepted,
        )
    }

    @Test
    fun endpoint_claim_requires_exact_transcribing_fence_before_commit() {
        val controller = GlassesModeController()
        val listening = startListening(controller, "stored", "runtime")
        val fence =
            checkNotNull(
                controller.beginTranscription(
                    listening.generation,
                    checkNotNull(listening.activeStreamId),
                    "utterance",
                ),
            )

        assertEquals(GlassesModeState.TRANSCRIBING, controller.snapshot.value.state)
        assertFalse(controller.completeTranscript(fence, " ").accepted)
        assertEquals(GlassesModeState.LISTENING, controller.snapshot.value.state)
        assertFalse(controller.isTranscriptFenceActive(fence))
    }

    @Test
    fun submission_failure_releases_the_matching_awaiting_transcript_fence() {
        val controller = GlassesModeController()
        val listening = startListening(controller, "stored", "runtime")
        val fence =
            checkNotNull(
                controller.acceptTranscript(
                    listening.generation,
                    checkNotNull(listening.activeStreamId),
                    "utterance",
                    "hello",
                ).fence,
            )

        assertTrue(controller.failSubmission(fence, "Voice submission failed"))
        assertEquals(GlassesModeState.SUSPENDED, controller.snapshot.value.state)
        assertFalse(controller.isTranscriptFenceActive(fence))
        assertNull(controller.snapshot.value.pendingUtteranceId)
        assertNull(controller.snapshot.value.inFlightTurnId)
    }

    @Test
    fun accepted_transcript_fence_is_invalidated_by_end_or_chat_switch() {
        val controller = GlassesModeController()
        val first = startListening(controller, "stored-a", "runtime-a")
        val acceptance =
            controller.acceptTranscript(
                first.generation,
                checkNotNull(first.activeStreamId),
                "utterance-a",
                "hello",
            )

        val fence = checkNotNull(acceptance.fence)
        assertEquals(first.generation, fence.generation)
        assertEquals("stored-a", fence.storedSessionId)
        assertEquals("runtime-a", fence.runtimeSessionId)
        assertTrue(controller.isTranscriptFenceActive(fence))

        controller.start("stored-b", "runtime-b")

        assertFalse(controller.isTranscriptFenceActive(fence))
        assertEquals("stored-b", controller.snapshot.value.storedSessionId)
    }

    @Test
    fun accepted_transcript_closes_its_stream_and_matching_response_opens_one_fresh_stream() {
        val controller = GlassesModeController()
        val initial = startListening(controller, "stored", "runtime")
        val initialStreamId = initial.activeStreamId!!
        assertTrue(initialStreamId.isNotBlank())

        assertTrue(
            controller.acceptTranscript(initial.generation, initialStreamId, "utterance", "hello").accepted,
        )
        assertNull(controller.snapshot.value.activeStreamId)
        assertFalse(
            controller.acceptTranscript(initial.generation, initialStreamId, "late-utterance", "late").accepted,
        )

        assertTrue(controller.acceptTerminal(initial.generation, "stored", "runtime", "response"))
        assertFalse(controller.acceptTerminal(initial.generation, "stored", "runtime", "duplicate response"))
        assertTrue(controller.displayCompleted(initial.generation, "stored", "runtime"))
        val nextStreamId = requireNotNull(controller.snapshot.value.activeStreamId)
        assertTrue(nextStreamId.isNotBlank())
        assertNotEquals(initialStreamId, nextStreamId)
        assertFalse(controller.displayCompleted(initial.generation, "stored", "runtime"))
        assertEquals(nextStreamId, controller.snapshot.value.activeStreamId)
        assertFalse(
            controller.acceptTranscript(initial.generation, initialStreamId, "late-after-display", "late").accepted,
        )
        assertTrue(
            controller.acceptTranscript(initial.generation, nextStreamId, "next-utterance", "second turn").accepted,
        )
        assertFalse(
            controller.acceptTranscript(initial.generation, nextStreamId, "duplicate-next", "duplicate").accepted,
        )
    }

    @Test
    fun switch_fences_old_callbacks_and_preserves_new_session() {
        val controller = GlassesModeController()
        val first = startListening(controller, "stored-a", "runtime-a")
        val second = startListening(controller, "stored-b", "runtime-b")

        assertTrue(second.generation > first.generation)
        assertFalse(controller.acceptTranscript(first.generation, "stream-a", "utterance-a", "old").accepted)
        assertTrue(
            controller.acceptTranscript(second.generation, second.activeStreamId!!, "utterance-b", "new").accepted,
        )
        assertEquals("stored-b", controller.snapshot.value.storedSessionId)
    }

    @Test
    fun only_exact_normalized_end_phrases_end_mode() {
        assertEndCommand("end glasses mode.")
        assertEndCommand(" Stop Glasses Mode! ")
        assertEndCommand("\tEND \nGLASSES MODE…\n")

        assertOrdinaryTranscript("please stop glasses mode.")
        assertOrdinaryTranscript("stop glasses mode later")
        assertOrdinaryTranscript("stop, glasses mode")
    }

    @Test
    fun phone_priority_cancels_uncommitted_voice_and_terminal_is_fenced() {
        val controller = GlassesModeController()
        val started = startListening(controller, "stored", "runtime")

        assertTrue(controller.claimPhonePriority("stored", "runtime"))
        assertEquals(GlassesModeState.PHONE_PRIORITY, controller.snapshot.value.state)
        assertFalse(controller.acceptTranscript(started.generation, "stream", "voice", "late voice").accepted)
        assertFalse(controller.acceptTerminal(started.generation, "stored", "wrong-runtime", "reply"))
        assertTrue(controller.acceptTerminal(started.generation, "stored", "runtime", "reply"))
        assertEquals(GlassesModeState.RENDERING, controller.snapshot.value.state)
        assertTrue(controller.displayCompleted(started.generation, "stored", "runtime"))
        assertEquals(GlassesModeState.LISTENING, controller.snapshot.value.state)
    }

    @Test
    fun dependency_suspend_and_recover_never_restart_inactive_capture() {
        val controller = GlassesModeController()
        controller.suspend("host disconnected")
        assertEquals(GlassesModeState.INACTIVE, controller.snapshot.value.state)

        val started = startListening(controller, "stored", "runtime")
        controller.suspend("host disconnected")
        assertEquals(GlassesModeState.SUSPENDED, controller.snapshot.value.state)
        assertTrue(controller.recover(started.generation, "stored", "runtime"))
        assertEquals(GlassesModeState.LISTENING, controller.snapshot.value.state)
        controller.end()
        assertFalse(controller.recover(started.generation, "stored", "runtime"))
    }

    @Test
    fun start_waits_for_initial_display_before_listening() {
        val controller = GlassesModeController()
        val started = controller.start("stored", "runtime")

        assertEquals(GlassesModeState.STARTING, started.state)
        assertFalse(controller.acceptTranscript(started.generation, "stream", "voice", "late").accepted)
        assertTrue(controller.initialDisplayCompleted(started.generation, "stored", "runtime"))
        assertEquals(GlassesModeState.LISTENING, controller.snapshot.value.state)
        assertTrue(controller.snapshot.value.activeStreamId != null)
    }

    @Test
    fun phone_mirror_acceptance_requires_the_current_phone_priority_fence_and_deduplicates_id() {
        val controller = GlassesModeController()
        val started = startListening(controller, "stored", "runtime")

        assertFalse(controller.acceptPhoneMirror(started.generation, "stored", "runtime", "mirror-1"))
        assertTrue(controller.claimPhonePriority("stored", "runtime"))
        assertTrue(controller.acceptPhoneMirror(started.generation, "stored", "runtime", "mirror-1"))
        assertFalse(controller.acceptPhoneMirror(started.generation, "stored", "runtime", "mirror-1"))
        assertFalse(controller.acceptPhoneMirror(started.generation - 1, "stored", "runtime", "mirror-2"))
        assertTrue(controller.recoverPhonePriority(started.generation, "stored", "runtime"))
        assertEquals(GlassesModeState.LISTENING, controller.snapshot.value.state)
    }

    private fun startListening(
        controller: GlassesModeController,
        storedSessionId: String,
        runtimeSessionId: String,
    ): GlassesModeSnapshot {
        val starting = controller.start(storedSessionId, runtimeSessionId)
        assertTrue(
            controller.initialDisplayCompleted(
                starting.generation,
                storedSessionId,
                runtimeSessionId,
            ),
        )
        return controller.snapshot.value
    }

    private fun assertEndCommand(text: String) {
        val controller = GlassesModeController()
        val listening = startListening(controller, "stored", "runtime")
        val fence =
            checkNotNull(
                controller.beginTranscription(
                    listening.generation,
                    checkNotNull(listening.activeStreamId),
                    "utterance",
                ),
            )

        val acceptance = controller.completeTranscript(fence, text)

        assertFalse(acceptance.accepted)
        assertTrue(acceptance.ended)
        assertEquals(GlassesModeState.INACTIVE, controller.snapshot.value.state)
    }

    private fun assertOrdinaryTranscript(text: String) {
        val controller = GlassesModeController()
        val listening = startListening(controller, "stored", "runtime")
        val fence =
            checkNotNull(
                controller.beginTranscription(
                    listening.generation,
                    checkNotNull(listening.activeStreamId),
                    "utterance",
                ),
            )

        val acceptance = controller.completeTranscript(fence, text)

        assertTrue(acceptance.accepted)
        assertFalse(acceptance.ended)
        assertEquals(GlassesModeState.AWAITING_HERMES, controller.snapshot.value.state)
    }
}
