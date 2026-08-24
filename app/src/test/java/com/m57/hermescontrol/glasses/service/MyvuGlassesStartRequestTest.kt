package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.GlassesInitialDisplayKind
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuGlassesStartRequestTest {
    @Test
    fun accepts_a_complete_typed_initial_display_without_a_bridge_token() {
        assertTrue(
            MyvuGlassesStartRequest(
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "You:\nprompt\n\nHermes:\nresponse",
                initialDisplayKind = GlassesInitialDisplayKind.COMPLETED_RESPONSE,
            ).isValid,
        )
    }

    @Test
    fun reads_the_typed_initial_display_extra() {
        val intent = mockk<android.content.Intent>()
        every { intent.getStringExtra(MyvuGlassesService.EXTRA_STORED_SESSION_ID) } returns "stored"
        every { intent.getStringExtra(MyvuGlassesService.EXTRA_RUNTIME_SESSION_ID) } returns "runtime"
        every { intent.getStringExtra(MyvuGlassesService.EXTRA_INITIAL_DISPLAY) } returns "response"
        every { intent.getStringExtra(MyvuGlassesService.EXTRA_INITIAL_DISPLAY_KIND) } returns
            GlassesInitialDisplayKind.COMPLETED_RESPONSE.name

        val request = intent.toMyvuGlassesStartRequest()

        assertEquals(GlassesInitialDisplayKind.COMPLETED_RESPONSE, request.initialDisplayKind)
        assertTrue(request.isValid)
    }

    @Test
    fun rejects_blank_initial_display_missing_session_identity_or_missing_display_kind() {
        assertFalse(
            MyvuGlassesStartRequest(
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "   ",
                initialDisplayKind = GlassesInitialDisplayKind.NEUTRAL,
            ).isValid,
        )
        assertFalse(
            MyvuGlassesStartRequest(
                storedSessionId = null,
                runtimeSessionId = "runtime",
                initialDisplay = "display",
                initialDisplayKind = GlassesInitialDisplayKind.NEUTRAL,
            ).isValid,
        )
        assertFalse(
            MyvuGlassesStartRequest(
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                initialDisplay = "display",
                initialDisplayKind = null,
            ).isValid,
        )
    }
}
