package com.m57.hermescontrol.glasses

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class GlassesModeState {
    INACTIVE,
    STARTING,
    LISTENING,
    TRANSCRIBING,
    AWAITING_HERMES,
    RENDERING,
    PHONE_PRIORITY,
    SUSPENDED,
    ERROR,
}

data class GlassesModeSnapshot(
    val generation: Long = 0,
    val storedSessionId: String? = null,
    val runtimeSessionId: String? = null,
    val state: GlassesModeState = GlassesModeState.INACTIVE,
    val activeStreamId: String? = null,
    val pendingUtteranceId: String? = null,
    val inFlightTurnId: String? = null,
    val lastTerminalKey: String? = null,
    val lastPhoneMirrorId: String? = null,
    val detail: String? = null,
)

data class TranscriptFence(
    val generation: Long,
    val storedSessionId: String,
    val runtimeSessionId: String,
    val utteranceId: String,
)

data class TranscriptAcceptance(
    val accepted: Boolean,
    val ended: Boolean = false,
    val reason: String? = null,
    val fence: TranscriptFence? = null,
)

/**
 * Process-owned state machine for a single glasses session. Network and Android
 * effects live at the edges; this class makes all callback fencing explicit.
 */
class GlassesModeController {
    private val _snapshot = MutableStateFlow(GlassesModeSnapshot())
    val snapshot: StateFlow<GlassesModeSnapshot> = _snapshot.asStateFlow()

    @Synchronized
    fun start(
        storedSessionId: String,
        runtimeSessionId: String,
    ): GlassesModeSnapshot {
        val next = _snapshot.value.generation + 1
        return GlassesModeSnapshot(
            generation = next,
            storedSessionId = storedSessionId,
            runtimeSessionId = runtimeSessionId,
            state = GlassesModeState.STARTING,
        ).also { _snapshot.value = it }
    }

    @Synchronized
    fun initialDisplayCompleted(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean {
        val current = _snapshot.value
        if (
            !current.matches(generation, storedSessionId, runtimeSessionId) ||
            current.state != GlassesModeState.STARTING
        ) {
            return false
        }
        _snapshot.value = current.openListeningEpoch()
        return true
    }

    @Synchronized
    fun beginTranscription(
        generation: Long,
        streamId: String,
        utteranceId: String,
    ): TranscriptFence? {
        val current = _snapshot.value
        if (
            !current.matches(generation) ||
            current.state != GlassesModeState.LISTENING ||
            current.activeStreamId != streamId
        ) {
            return null
        }
        val fence =
            TranscriptFence(
                generation = current.generation,
                storedSessionId = checkNotNull(current.storedSessionId),
                runtimeSessionId = checkNotNull(current.runtimeSessionId),
                utteranceId = utteranceId,
            )
        _snapshot.value =
            current.copy(
                state = GlassesModeState.TRANSCRIBING,
                activeStreamId = null,
                pendingUtteranceId = utteranceId,
                inFlightTurnId = "${current.generation}:$utteranceId",
            )
        return fence
    }

    @Synchronized
    fun completeTranscript(
        fence: TranscriptFence,
        text: String,
    ): TranscriptAcceptance {
        val current = _snapshot.value
        if (!current.matches(fence.generation, fence.storedSessionId, fence.runtimeSessionId) ||
            current.state != GlassesModeState.TRANSCRIBING ||
            current.pendingUtteranceId != fence.utteranceId ||
            current.inFlightTurnId != "${fence.generation}:${fence.utteranceId}"
        ) {
            return TranscriptAcceptance(false, reason = "stale transcription fence")
        }
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            _snapshot.value = current.openListeningEpoch()
            return TranscriptAcceptance(false, reason = "empty transcript")
        }
        if (isEndPhrase(normalized)) {
            end()
            return TranscriptAcceptance(false, ended = true)
        }
        _snapshot.value = current.copy(state = GlassesModeState.AWAITING_HERMES, activeStreamId = null)
        return TranscriptAcceptance(true, fence = fence)
    }

    @Synchronized
    fun failTranscript(
        fence: TranscriptFence,
        detail: String,
    ): Boolean {
        val current = _snapshot.value
        if (!current.matches(fence.generation, fence.storedSessionId, fence.runtimeSessionId) ||
            current.state != GlassesModeState.TRANSCRIBING ||
            current.pendingUtteranceId != fence.utteranceId
        ) {
            return false
        }
        _snapshot.value = current.openListeningEpoch().copy(detail = detail)
        return true
    }

    @Synchronized
    fun failSubmission(
        fence: TranscriptFence,
        detail: String,
    ): Boolean {
        val current = _snapshot.value
        if (!isTranscriptFenceActive(fence)) return false
        _snapshot.value =
            current.copy(
                state = GlassesModeState.SUSPENDED,
                pendingUtteranceId = null,
                inFlightTurnId = null,
                detail = detail,
            )
        return true
    }

    @Synchronized
    fun acceptTranscript(
        generation: Long,
        streamId: String,
        utteranceId: String,
        text: String,
    ): TranscriptAcceptance {
        val current = _snapshot.value
        if (!current.matches(generation)) return TranscriptAcceptance(false, reason = "stale generation")
        if (current.state != GlassesModeState.LISTENING) return TranscriptAcceptance(false, reason = "not listening")
        if (current.activeStreamId != streamId) return TranscriptAcceptance(false, reason = "stale stream")
        if (isEndPhrase(text)) {
            end()
            return TranscriptAcceptance(false, ended = true)
        }
        val fence =
            TranscriptFence(
                generation = current.generation,
                storedSessionId = checkNotNull(current.storedSessionId),
                runtimeSessionId = checkNotNull(current.runtimeSessionId),
                utteranceId = utteranceId,
            )
        _snapshot.value =
            current.copy(
                state = GlassesModeState.AWAITING_HERMES,
                activeStreamId = null,
                pendingUtteranceId = utteranceId,
                inFlightTurnId = "$generation:$utteranceId",
            )
        return TranscriptAcceptance(true, fence = fence)
    }

    @Synchronized
    fun isTranscriptFenceActive(fence: TranscriptFence): Boolean {
        val current = _snapshot.value
        return current.matches(fence.generation, fence.storedSessionId, fence.runtimeSessionId) &&
            current.state == GlassesModeState.AWAITING_HERMES &&
            current.pendingUtteranceId == fence.utteranceId &&
            current.inFlightTurnId == "${fence.generation}:${fence.utteranceId}"
    }

    @Synchronized
    fun claimPhonePriority(
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean {
        val current = _snapshot.value
        if (current.state == GlassesModeState.INACTIVE ||
            current.storedSessionId != storedSessionId ||
            current.runtimeSessionId != runtimeSessionId
        ) {
            return false
        }
        _snapshot.value =
            current.copy(
                state = GlassesModeState.PHONE_PRIORITY,
                activeStreamId = null,
                pendingUtteranceId = null,
                inFlightTurnId = null,
            )
        return true
    }

    @Synchronized
    fun acceptPhoneMirror(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
        mirrorId: String,
    ): Boolean {
        val current = _snapshot.value
        if (
            mirrorId.isBlank() ||
            !current.matches(generation, storedSessionId, runtimeSessionId) ||
            current.state != GlassesModeState.PHONE_PRIORITY ||
            current.lastPhoneMirrorId == mirrorId
        ) {
            return false
        }
        _snapshot.value = current.copy(lastPhoneMirrorId = mirrorId)
        return true
    }

    @Synchronized
    fun recoverPhonePriority(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean {
        val current = _snapshot.value
        if (
            !current.matches(generation, storedSessionId, runtimeSessionId) ||
            current.state != GlassesModeState.PHONE_PRIORITY
        ) {
            return false
        }
        _snapshot.value = current.openListeningEpoch()
        return true
    }

    @Synchronized
    fun acceptTerminal(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
        terminalText: String,
    ): Boolean {
        val current = _snapshot.value
        if (
            !current.matches(generation, storedSessionId, runtimeSessionId) ||
            (
                current.state != GlassesModeState.AWAITING_HERMES &&
                    current.state != GlassesModeState.PHONE_PRIORITY
            )
        ) {
            return false
        }
        _snapshot.value =
            current.copy(
                state = GlassesModeState.RENDERING,
                lastTerminalKey = "$generation:${terminalText.hashCode()}",
            )
        return true
    }

    @Synchronized
    fun displayCompleted(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean {
        val current = _snapshot.value
        if (!current.matches(
                generation,
                storedSessionId,
                runtimeSessionId,
            ) || current.state != GlassesModeState.RENDERING
        ) {
            return false
        }
        _snapshot.value = current.openListeningEpoch()
        return true
    }

    @Synchronized
    fun error(detail: String) {
        val current = _snapshot.value
        if (current.state != GlassesModeState.INACTIVE) {
            _snapshot.value =
                current.copy(
                    state = GlassesModeState.ERROR,
                    activeStreamId = null,
                    pendingUtteranceId = null,
                    inFlightTurnId = null,
                    detail = detail,
                )
        }
    }

    @Synchronized
    fun suspend(detail: String) {
        val current = _snapshot.value
        if (current.state != GlassesModeState.INACTIVE) {
            _snapshot.value =
                current.copy(
                    state = GlassesModeState.SUSPENDED,
                    detail = detail,
                )
        }
    }

    @Synchronized
    fun recover(
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
    ): Boolean {
        val current = _snapshot.value
        if (!current.matches(
                generation,
                storedSessionId,
                runtimeSessionId,
            ) || current.state != GlassesModeState.SUSPENDED
        ) {
            return false
        }
        _snapshot.value = current.openListeningEpoch().copy(detail = null)
        return true
    }

    @Synchronized
    fun end() {
        val nextGeneration = _snapshot.value.generation + 1
        _snapshot.value = GlassesModeSnapshot(generation = nextGeneration)
    }

    private fun GlassesModeSnapshot.openListeningEpoch(): GlassesModeSnapshot =
        copy(
            state = GlassesModeState.LISTENING,
            activeStreamId = UUID.randomUUID().toString(),
            pendingUtteranceId = null,
            inFlightTurnId = null,
        )

    private fun GlassesModeSnapshot.matches(
        generation: Long,
        storedSessionId: String? = null,
        runtimeSessionId: String? = null,
    ): Boolean =
        this.generation == generation &&
            (storedSessionId == null || this.storedSessionId == storedSessionId) &&
            (runtimeSessionId == null || this.runtimeSessionId == runtimeSessionId)

    private fun isEndPhrase(text: String): Boolean =
        text
            .trim()
            .lowercase()
            .replace(WHITESPACE, " ")
            .trim { it in END_PHRASE_EDGE_PUNCTUATION }
            .trim() in END_PHRASES

    private companion object {
        val WHITESPACE = Regex("\\s+")
        const val END_PHRASE_EDGE_PUNCTUATION = ".!?…"
        val END_PHRASES = setOf("end glasses mode", "stop glasses mode")
    }
}
