package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.GlassesModeSnapshot
import com.m57.hermescontrol.glasses.GlassesModeState
import com.m57.hermescontrol.glasses.TurnLease
import com.m57.hermescontrol.glasses.TurnSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuTerminalLeaseReleaseTest {
    @Test
    fun failedVoiceLeaseReleaseDoesNotCompleteTerminalCallback() =
        runTest {
            val released =
                terminalLeaseReleased(
                    snapshot = activeSnapshot(),
                    voiceLease = voiceLease(),
                    text = "Final",
                    completeVoice = { _, _, _ -> false },
                    completePhone = { _, _ -> error("phone lease must not be released") },
                )

            assertFalse(released)
        }

    @Test
    fun missingPhoneLeaseDoesNotCompleteTerminalCallback() =
        runTest {
            val released =
                terminalLeaseReleased(
                    snapshot = activeSnapshot().copy(state = GlassesModeState.PHONE_PRIORITY),
                    voiceLease = null,
                    text = "Final",
                    completeVoice = { _, _, _ -> error("voice lease must not be released") },
                    completePhone = { _, _ -> null },
                )

            assertFalse(released)
        }

    @Test
    fun releasedVoiceLeaseCompletesTerminalCallback() =
        runTest {
            val released =
                terminalLeaseReleased(
                    snapshot = activeSnapshot(),
                    voiceLease = voiceLease(),
                    text = "Final",
                    completeVoice = { _, _, _ -> true },
                    completePhone = { _, _ -> error("phone lease must not be released") },
                )

            assertTrue(released)
        }

    @Test
    fun releasedPhoneLeaseCompletesTerminalCallback() =
        runTest {
            val released =
                terminalLeaseReleased(
                    snapshot = activeSnapshot().copy(state = GlassesModeState.PHONE_PRIORITY),
                    voiceLease = null,
                    text = "Final",
                    completeVoice = { _, _, _ -> error("voice lease must not be released") },
                    completePhone = { _, _ -> phoneLease() },
                )

            assertTrue(released)
        }

    private fun activeSnapshot() =
        GlassesModeSnapshot(
            generation = 7,
            storedSessionId = "stored",
            runtimeSessionId = "runtime",
            state = GlassesModeState.AWAITING_HERMES,
        )

    private fun voiceLease() =
        TurnLease(
            id = "voice",
            storedSessionId = "stored",
            runtimeSessionId = "runtime",
            source = TurnSource.VOICE,
        )

    private fun phoneLease() =
        TurnLease(
            id = "phone",
            storedSessionId = "stored",
            runtimeSessionId = "runtime",
            source = TurnSource.PHONE,
        )
}
