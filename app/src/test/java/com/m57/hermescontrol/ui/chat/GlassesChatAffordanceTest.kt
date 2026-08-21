package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.glasses.GlassesModeSnapshot
import com.m57.hermescontrol.glasses.GlassesModeState
import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesChatAffordanceTest {
    @Test
    fun maps_only_current_established_transport_states_to_connected() {
        val connectedStates =
            setOf(
                GlassesModeState.LISTENING,
                GlassesModeState.TRANSCRIBING,
                GlassesModeState.AWAITING_HERMES,
                GlassesModeState.RENDERING,
                GlassesModeState.PHONE_PRIORITY,
            )
        GlassesModeState.entries.forEach { modeState ->
            val expected =
                if (modeState in connectedStates) {
                    GlassesChatAffordance.Connected
                } else if (modeState == GlassesModeState.INACTIVE) {
                    GlassesChatAffordance.Start
                } else {
                    when (modeState) {
                        GlassesModeState.STARTING -> GlassesChatAffordance.Starting
                        GlassesModeState.SUSPENDED -> GlassesChatAffordance.Suspended
                        GlassesModeState.ERROR -> GlassesChatAffordance.Error
                        else -> error("Unexpected mode state: $modeState")
                    }
                }
            assertEquals(
                expected,
                glassesChatAffordance(
                    GlassesModeSnapshot(
                        storedSessionId = "current",
                        state = modeState,
                    ),
                    "current",
                ),
            )
        }
    }

    @Test
    fun maps_another_active_chat_to_switch() {
        assertEquals(
            GlassesChatAffordance.Switch,
            glassesChatAffordance(
                GlassesModeSnapshot(
                    storedSessionId = "other",
                    state = GlassesModeState.LISTENING,
                ),
                "current",
            ),
        )
    }
}
