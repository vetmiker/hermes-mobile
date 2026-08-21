package com.m57.hermescontrol.ui.chat

import com.m57.hermescontrol.ui.chat.fullbleed.AgentEntry
import com.m57.hermescontrol.ui.chat.fullbleed.ChatTurn
import com.m57.hermescontrol.ui.chat.fullbleed.groupIntoTurns

/**
 * Produces the immutable context shown when a chat is handed to the glasses.
 *
 * The full-bleed renderer is the canonical authority for which rows are user
 * turns, assistant prose, tool rows, and synthetic/system events. Reusing its
 * grouping keeps timeline markers and tool-rich turns out of the prompt slot
 * while retaining every completed assistant prose segment in the response.
 */
internal fun initialGlassesDisplay(
    messages: List<ChatMessage>,
    isAgentTyping: Boolean = false,
    streamingMessage: ChatMessage? = null,
): String? {
    if (isAgentTyping || streamingMessage != null) return null

    val turns = groupIntoTurns(messages)
    val userTurnIndex = turns.indexOfLast { it is ChatTurn.User }
    val userTurn = turns.getOrNull(userTurnIndex) as? ChatTurn.User ?: return null
    val agentTurn = turns.getOrNull(userTurnIndex + 1) as? ChatTurn.Agent ?: return null
    val prompt = userTurn.message.content.trim()
    if (prompt.isBlank()) return null

    if (agentTurn.entries.any(::isIncompleteAgentEntry)) return null
    val response =
        agentTurn.entries
            .filterIsInstance<AgentEntry.Prose>()
            .map { it.message.content.trim() }
            .filter(String::isNotBlank)
            .joinToString(separator = "\n\n")
    if (response.isBlank()) return null

    return "You:\n$prompt\n\nHermes:\n$response"
}

private fun isIncompleteAgentEntry(entry: AgentEntry): Boolean =
    when (entry) {
        is AgentEntry.Prose -> entry.message.isStreaming
        is AgentEntry.ToolRow -> entry.message.toolStatus == ToolStatus.RUNNING
        is AgentEntry.SystemEvent -> false
    }
