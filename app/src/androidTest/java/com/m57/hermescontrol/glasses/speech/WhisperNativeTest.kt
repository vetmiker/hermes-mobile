package com.m57.hermescontrol.glasses.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhisperNativeTest {
    @Test
    fun loads_pinned_library_and_rejects_missing_models_without_creating_a_handle() {
        assertEquals("1.9.3", WhisperNative.version())

        val failure =
            runCatching {
                WhisperNative.open(
                    "/missing-whisper.bin",
                    "/missing-vad.bin",
                    1,
                )
            }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    @Test
    fun close_is_idempotent_for_an_absent_handle() {
        WhisperNative.close(0)
        WhisperNative.close(0)
    }
}
