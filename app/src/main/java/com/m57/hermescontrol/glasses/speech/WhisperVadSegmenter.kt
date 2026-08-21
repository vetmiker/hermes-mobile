package com.m57.hermescontrol.glasses.speech

import java.io.ByteArrayOutputStream

/** Kotlin-owned endpoint policy over copied VAD probabilities. */
internal class WhisperVadSegmenter(
    private val threshold: Float = 0.5f,
    private val frameMillis: Int = 20,
    private val minimumSpeechMillis: Int = 600,
    private val terminalSilenceMillis: Int = 2_000,
    private val maximumUtteranceMillis: Int = 30_000,
    private val preRollMillis: Int = 300,
) {
    sealed interface Event {
        data class Utterance(val pcm: ByteArray) : Event

        data object Discarded : Event
    }

    private val preRoll = ArrayDeque<ByteArray>()
    private val utterance = ArrayList<ByteArray>()
    private var speechMillis = 0
    private var silenceMillis = 0
    private var utteranceMillis = 0

    fun accept(
        frame: ByteArray,
        probability: Float,
    ): Event? {
        val copy = frame.copyOf()
        if (utterance.isEmpty()) {
            preRoll.addLast(copy)
            while (preRoll.size * frameMillis > preRollMillis) preRoll.removeFirst()
            if (probability < threshold) return null
            utterance.addAll(preRoll)
            preRoll.clear()
        } else {
            utterance.add(copy)
        }
        utteranceMillis += frameMillis
        if (probability >= threshold) {
            speechMillis += frameMillis
            silenceMillis = 0
        } else {
            silenceMillis += frameMillis
        }
        return when {
            utteranceMillis >= maximumUtteranceMillis -> close()
            silenceMillis >= terminalSilenceMillis -> close()
            else -> null
        }
    }

    fun reset() {
        preRoll.clear()
        utterance.clear()
        speechMillis = 0
        silenceMillis = 0
        utteranceMillis = 0
    }

    private fun close(): Event {
        val event =
            if (speechMillis >= minimumSpeechMillis) {
                val output = ByteArrayOutputStream(utterance.sumOf(ByteArray::size))
                utterance.forEach(output::write)
                Event.Utterance(output.toByteArray())
            } else {
                Event.Discarded
            }
        reset()
        return event
    }
}
