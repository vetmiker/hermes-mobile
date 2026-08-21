package com.m57.hermescontrol.glasses.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuGlassesStartRequestTest {
    @Test
    fun accepts_a_complete_initial_display_with_authenticated_session_ids() {
        assertTrue(
            MyvuGlassesStartRequest(
                token = "token",
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "You:\nprompt\n\nHermes:\nresponse",
            ).isValid,
        )
    }

    @Test
    fun rejects_blank_initial_display_alongside_missing_start_credentials() {
        assertFalse(
            MyvuGlassesStartRequest(
                token = "token",
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "   ",
            ).isValid,
        )
        assertFalse(
            MyvuGlassesStartRequest(
                token = "",
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "display",
            ).isValid,
        )
    }
}
