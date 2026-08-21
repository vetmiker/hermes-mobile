package com.m57.hermescontrol.glasses.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperVadSegmenterTest {
    @Test
    fun rejects_599ms_blip_and_accepts_600ms_after_terminal_silence() {
        val rejected = WhisperVadSegmenter()
        repeat(29) { rejected.accept(frame(), 0.9f) }
        val rejectedEvent = closeWithSilence(rejected)
        assertEquals(WhisperVadSegmenter.Event.Discarded, rejectedEvent)

        val accepted = WhisperVadSegmenter()
        repeat(30) { accepted.accept(frame(), 0.9f) }
        val event = closeWithSilence(accepted)
        assertTrue(event is WhisperVadSegmenter.Event.Utterance)
    }

    @Test
    fun exact_32ms_vad_windows_preserve_minimum_speech_duration() {
        val segmenter = WhisperVadSegmenter(frameMillis = 32)
        repeat(18) { segmenter.accept(ByteArray(1_024), 0.9f) }
        repeat(62) { segmenter.accept(ByteArray(1_024), 0.1f) }
        assertEquals(WhisperVadSegmenter.Event.Discarded, segmenter.accept(ByteArray(1_024), 0.1f))

        repeat(19) { segmenter.accept(ByteArray(1_024), 0.9f) }
        repeat(62) { segmenter.accept(ByteArray(1_024), 0.1f) }
        assertTrue(segmenter.accept(ByteArray(1_024), 0.1f) is WhisperVadSegmenter.Event.Utterance)
    }

    @Test
    fun does_not_close_during_shorter_pause_and_force_closes_at_thirty_seconds() {
        val segmenter = WhisperVadSegmenter()
        repeat(30) { segmenter.accept(frame(), 0.9f) }
        repeat(99) { assertNull(segmenter.accept(frame(), 0.1f)) }
        assertNull(segmenter.accept(frame(), 0.9f))

        val capped = WhisperVadSegmenter()
        repeat(1_499) { capped.accept(frame(), 0.9f) }
        assertTrue(capped.accept(frame(), 0.9f) is WhisperVadSegmenter.Event.Utterance)
    }

    @Test
    fun threshold_is_inclusive_and_retains_bounded_preroll_pcm() {
        val segmenter = WhisperVadSegmenter(preRollMillis = 40, terminalSilenceMillis = 20)
        segmenter.accept(ByteArray(640) { 1 }, 0.49f)
        segmenter.accept(ByteArray(640) { 2 }, 0.49f)
        segmenter.accept(ByteArray(640) { 3 }, 0.5f)
        repeat(29) { segmenter.accept(frame(), 0.5f) }

        val event = segmenter.accept(frame(), 0.1f)

        assertTrue(event is WhisperVadSegmenter.Event.Utterance)
        assertEquals(2.toByte(), (event as WhisperVadSegmenter.Event.Utterance).pcm[0])
    }

    private fun closeWithSilence(segmenter: WhisperVadSegmenter): WhisperVadSegmenter.Event? {
        repeat(99) { segmenter.accept(frame(), 0.1f) }
        return segmenter.accept(frame(), 0.1f)
    }

    private fun frame() = ByteArray(640)
}
