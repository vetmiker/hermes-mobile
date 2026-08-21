package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.glasses.GlassesModeSnapshot
import com.m57.hermescontrol.glasses.GlassesModeState

/**
 * Fences the gateway's ordered same-socket stream to one glasses turn.
 *
 * Token and tool events have no server turn ID, so MessageStart opens the only
 * accepted epoch and every later event must match its session and controller
 * generation. The service owns lifecycle effects; this router only decides
 * which projection calls are valid.
 */
internal class MyvuSessionEventRouter(
    private val publisher: MyvuTurnPublisher,
    private val currentSnapshot: () -> GlassesModeSnapshot,
    private val onFinalDelivered: (GlassesModeSnapshot, String) -> Unit,
) {
    private var epoch: Epoch? = null

    fun route(event: WsEvent) {
        val snapshot = currentSnapshot()
        when (event) {
            is WsEvent.MessageStart -> {
                if (!isEligible(snapshot, event.sessionId)) return
                epoch = Epoch(snapshot.generation, checkNotNull(snapshot.runtimeSessionId))
                publisher.startEpoch()
            }

            is WsEvent.MessageToken -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToken(event.token)
            }

            is WsEvent.ToolStart -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToolStart(event.name, event.data)
            }

            is WsEvent.ToolGenerating -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToolGenerating(event.name)
            }

            is WsEvent.ToolProgress -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToolProgress(event.name, event.preview)
            }

            is WsEvent.ToolComplete -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToolComplete(event.name)
            }

            is WsEvent.ToolOutputRisk -> {
                if (matchesEpoch(snapshot, event.sessionId)) publisher.publishToolRisk(event.name)
            }

            is WsEvent.MessageComplete -> {
                if (!matchesEpoch(snapshot, event.sessionId) || event.text.isEmpty()) return
                epoch = null
                publisher.publishFinal(event.text) { onFinalDelivered(snapshot, event.text) }
            }

            else -> Unit
        }
    }

    fun close() {
        epoch = null
        publisher.close()
    }

    private fun matchesEpoch(
        snapshot: GlassesModeSnapshot,
        eventSessionId: String?,
    ): Boolean {
        val activeEpoch = epoch ?: return false
        return isEligible(snapshot, eventSessionId) &&
            activeEpoch.generation == snapshot.generation &&
            activeEpoch.runtimeSessionId == snapshot.runtimeSessionId
    }

    private fun isEligible(
        snapshot: GlassesModeSnapshot,
        eventSessionId: String?,
    ): Boolean =
        !snapshot.runtimeSessionId.isNullOrBlank() &&
            snapshot.runtimeSessionId == eventSessionId &&
            (
                snapshot.state == GlassesModeState.AWAITING_HERMES ||
                    snapshot.state == GlassesModeState.PHONE_PRIORITY
            )

    private data class Epoch(
        val generation: Long,
        val runtimeSessionId: String,
    )
}
