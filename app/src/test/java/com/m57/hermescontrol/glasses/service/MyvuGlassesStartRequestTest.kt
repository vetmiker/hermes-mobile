package com.m57.hermescontrol.glasses.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuGlassesStartRequestTest {
    @Test
    fun accepts_a_complete_initial_display_without_a_bridge_token() {
        assertTrue(
            MyvuGlassesStartRequest(
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "You:\nprompt\n\nHermes:\nresponse",
            ).isValid,
        )
    }

    @Test
    fun rejects_blank_initial_display_or_missing_session_identity() {
        assertFalse(
            MyvuGlassesStartRequest(
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "   ",
            ).isValid,
        )
        assertFalse(
            MyvuGlassesStartRequest(
                storedSessionId = null,
                runtimeSessionId = "runtime",
                initialDisplay = "display",
            ).isValid,
        )
    }
}
