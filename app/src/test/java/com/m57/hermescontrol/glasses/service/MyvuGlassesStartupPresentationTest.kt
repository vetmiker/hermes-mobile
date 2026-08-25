package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.GlassesInitialDisplayKind
import com.m57.hermescontrol.glasses.myvu.DisplayKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyvuGlassesStartupPresentationTest {
    @Test
    fun completed_response_opens_a_response_document_and_preserves_it_at_startup() {
        val presentation =
            myvuGlassesStartupPresentation(GlassesInitialDisplayKind.COMPLETED_RESPONSE)

        assertEquals(DisplayKind.Response, presentation.initialDisplayKind)
        assertNull(presentation.sessionLoadedDisplayKind)
    }

    @Test
    fun neutral_content_uses_context_and_shows_session_loaded_on_glasses() {
        val presentation = myvuGlassesStartupPresentation(GlassesInitialDisplayKind.NEUTRAL)

        assertEquals(DisplayKind.Context, presentation.initialDisplayKind)
        assertEquals(DisplayKind.Status, presentation.sessionLoadedDisplayKind)
    }
}
