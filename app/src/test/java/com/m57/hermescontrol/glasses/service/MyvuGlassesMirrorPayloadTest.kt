package com.m57.hermescontrol.glasses.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuGlassesMirrorPayloadTest {
    @Test
    fun accepts_complete_generation_fenced_visible_text_payload() {
        assertTrue(
            MyvuGlassesMirrorPayload(
                generation = 3,
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                mirrorId = "phone-1",
                text = "Visible prompt",
            ).isValid,
        )
    }

    @Test
    fun rejects_blank_or_incomplete_display_payloads() {
        assertFalse(
            MyvuGlassesMirrorPayload(
                generation = -1,
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                mirrorId = "phone-1",
                text = "Visible prompt",
            ).isValid,
        )
        assertFalse(
            MyvuGlassesMirrorPayload(
                generation = 3,
                storedSessionId = "stored",
                runtimeSessionId = "runtime",
                mirrorId = "phone-1",
                text = " ",
            ).isValid,
        )
    }
}
