package com.m57.hermescontrol.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesInitialDisplayTest {
    @Test
    fun rejects_no_completed_turn_or_typing_state() {
        val prompt = message("prompt", MessageRole.USER, "Draft a release note")

        assertNull(initialGlassesDisplay(emptyList()))
        assertNull(initialGlassesDisplay(listOf(prompt)))
        assertNull(
            initialGlassesDisplay(
                messages = listOf(prompt, message("partial", MessageRole.ASSISTANT, "Working", isStreaming = true)),
            ),
        )
        assertNull(
            initialGlassesDisplay(
                messages = listOf(prompt, message("answer", MessageRole.ASSISTANT, "Done")),
                isAgentTyping = true,
            ),
        )
    }

    @Test
    fun selects_the_newest_completed_full_turn() {
        val display =
            initialGlassesDisplay(
                listOf(
                    message("first-user", MessageRole.USER, "First prompt"),
                    message("first-assistant", MessageRole.ASSISTANT, "First answer"),
                    message("latest-user", MessageRole.USER, "Latest prompt"),
                    message("latest-assistant", MessageRole.ASSISTANT, "Latest answer"),
                ),
            )

        assertEquals("You:\nLatest prompt\n\nHermes:\nLatest answer", display)
    }

    @Test
    fun preserves_all_completed_prose_around_tool_rows_for_the_selected_turn() {
        val display =
            initialGlassesDisplay(
                listOf(
                    message("prompt", MessageRole.USER, "Investigate the build"),
                    message("preface", MessageRole.ASSISTANT, "I will inspect the logs."),
                    message("tool", MessageRole.TOOL, "{\"output\":\"...\"}"),
                    message("marker", MessageRole.USER, "Model changed").copy(displayKind = "model_switch"),
                    message("answer", MessageRole.ASSISTANT, "The build failed because tests are red."),
                ),
            )

        assertEquals(
            "You:\nInvestigate the build\n\nHermes:\n" +
                "I will inspect the logs.\n\nThe build failed because tests are red.",
            display,
        )
    }

    @Test
    fun rejects_a_newer_incomplete_turn_instead_of_replaying_an_older_turn() {
        val display =
            initialGlassesDisplay(
                listOf(
                    message("completed-user", MessageRole.USER, "Old prompt"),
                    message("completed-assistant", MessageRole.ASSISTANT, "Old answer"),
                    message("pending-user", MessageRole.USER, "New prompt"),
                ),
            )

        assertNull(display)
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        isStreaming: Boolean = false,
    ) = ChatMessage(id = id, role = role, content = content, isStreaming = isStreaming)
}
