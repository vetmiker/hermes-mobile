package com.m57.hermescontrol.glasses.service

import kotlinx.coroutines.Job
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
}
