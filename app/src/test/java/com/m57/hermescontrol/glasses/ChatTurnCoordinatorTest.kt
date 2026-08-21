package com.m57.hermescontrol.glasses

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnCoordinatorTest {
    @Test
    fun phone_and_notification_use_one_in_flight_submission_authority() =
        runTest {
            val gateway = RecordingGateway()
            val store = RecordingStore()
            val coordinator = ChatTurnCoordinator(gateway, store)

            val first = coordinator.submit(TurnRequest("stored", "runtime", "phone", TurnSource.PHONE))
            val overlapping = coordinator.submit(TurnRequest("stored", "runtime", "reply", TurnSource.NOTIFICATION))

            assertTrue(first.accepted)
            assertFalse(overlapping.accepted)
            assertEquals(listOf("prompt:runtime:phone"), gateway.calls)
            assertEquals(listOf("stored:phone"), store.records)

            assertTrue(coordinator.completeTerminal(first.lease!!, "runtime", "reply"))
            assertTrue(coordinator.submit(TurnRequest("stored", "runtime", "reply", TurnSource.NOTIFICATION)).accepted)
            assertEquals(listOf("prompt:runtime:phone", "prompt:runtime:reply"), gateway.calls)
        }

    @Test
    fun streaming_phone_turn_preserves_redirect_semantics() =
        runTest {
            val gateway = RecordingGateway()
            val coordinator = ChatTurnCoordinator(gateway, RecordingStore())

            val outcome =
                coordinator.submit(
                    TurnRequest("stored", "runtime", "correct", TurnSource.PHONE, isStreaming = true),
                )

            assertTrue(outcome.redirected)
            assertEquals(listOf("redirect:runtime:correct"), gateway.calls)
        }

    @Test
    fun terminal_event_frees_the_matching_active_lease() =
        runTest {
            val coordinator = ChatTurnCoordinator(RecordingGateway(), RecordingStore())
            val first = coordinator.submit(TurnRequest("stored", "runtime", "phone", TurnSource.PHONE))

            assertEquals(first.lease, coordinator.completeTerminalForRuntime("runtime", "reply"))
            assertTrue(coordinator.submit(TurnRequest("stored", "runtime", "next", TurnSource.PHONE)).accepted)
        }

    @Test
    fun streaming_phone_redirect_keeps_the_existing_lease_until_terminal() =
        runTest {
            val gateway = RecordingGateway()
            val coordinator = ChatTurnCoordinator(gateway, RecordingStore())
            val first = coordinator.submit(TurnRequest("stored", "runtime", "voice", TurnSource.VOICE))

            val redirect =
                coordinator.submit(
                    TurnRequest("stored", "runtime", "correct", TurnSource.PHONE, isStreaming = true),
                )

            assertTrue(redirect.accepted)
            assertTrue(redirect.redirected)
            assertEquals(first.lease, redirect.lease)
            assertEquals(listOf("prompt:runtime:voice", "redirect:runtime:correct"), gateway.calls)
            assertTrue(coordinator.completeTerminal(first.lease!!, "runtime", "reply"))
        }

    @Test
    fun phone_priority_cancels_uncommitted_voice_before_it_can_submit() =
        runTest {
            val gateway = RecordingGateway()
            val coordinator = ChatTurnCoordinator(gateway, RecordingStore())
            val reservation = coordinator.reserveVoice("stored", "runtime", "utterance")

            assertTrue(coordinator.claimPhonePriority("stored", "runtime"))
            assertFalse(coordinator.commitVoice(reservation, "voice").accepted)
            assertTrue(coordinator.submit(TurnRequest("stored", "runtime", "typed", TurnSource.PHONE)).accepted)
            assertEquals(listOf("prompt:runtime:typed"), gateway.calls)
        }

    @Test
    fun stale_or_duplicate_terminal_events_do_not_complete_active_lease() =
        runTest {
            val gateway = RecordingGateway()
            val coordinator = ChatTurnCoordinator(gateway, RecordingStore())
            val outcome = coordinator.submit(TurnRequest("stored", "runtime", "voice", TurnSource.VOICE))

            assertFalse(coordinator.completeTerminal(outcome.lease!!, "other-runtime", "reply"))
            assertTrue(coordinator.completeTerminal(outcome.lease, "runtime", "reply"))
            assertFalse(coordinator.completeTerminal(outcome.lease, "runtime", "reply"))
        }

    private class RecordingGateway : TurnGateway {
        val calls = mutableListOf<String>()

        override suspend fun submit(
            runtimeSessionId: String,
            text: String,
        ) {
            calls += "prompt:$runtimeSessionId:$text"
        }

        override suspend fun redirect(
            runtimeSessionId: String,
            text: String,
        ) {
            calls += "redirect:$runtimeSessionId:$text"
        }
    }

    private class RecordingStore : TurnStore {
        val records = mutableListOf<String>()

        override suspend fun persist(
            storedSessionId: String,
            text: String,
        ) {
            records += "$storedSessionId:$text"
        }
    }
}
