package com.m57.hermescontrol.glasses.myvu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MyvuDisplayRendererTest {
    @Test
    fun response_allocatesFreshDocumentAndSendsOpenThenContent() {
        val renderer = MyvuDisplayRenderer(documentId = { "next-document" })

        val commands = renderer.commandsFor("The complete Hermes response", DisplayKind.Response)
        assertEquals(3, commands.size)
        assertEquals("next-document/hermes-agent", commands[0].documentKey)
        assertEquals(commands[0].documentKey, commands[1].documentKey)
        assertEquals("com.upuphone.star.launcher", commands[0].receiverPackage)
        assertEquals("app", Json.parseToJsonElement(commands[0].payload).jsonObject["action"]?.toString()?.trim('"'))
        assertEquals("tici", Json.parseToJsonElement(commands[1].payload).jsonObject["action"]?.toString()?.trim('"'))
    }

    @Test
    fun statusReusesActiveDocumentButLaterResponseReplacesIt() {
        var sequence = 0
        val renderer = MyvuDisplayRenderer(documentId = { "document-${++sequence}" })

        val initial = renderer.commandsFor("Initial context", DisplayKind.Response)
        val status = renderer.commandsFor("Listening", DisplayKind.Status)
        val later = renderer.commandsFor("Later response", DisplayKind.Response)

        assertEquals(initial[0].documentKey, status[0].documentKey)
        assertNotEquals(initial[0].documentKey, later[0].documentKey)
    }

    @Test
    fun thinkingAndResponseEachMintOneIdentityWhenNoInputIsActive() {
        var documentNumber = 0
        val renderer = MyvuDisplayRenderer(documentId = { "document-${++documentNumber}" })

        val thinking = renderer.openThinking("Thinking", GlassesReadability())
        val thinkingUpdate = renderer.updateThinking("Thinking\n\n• read_file — Running")
        val response = renderer.openResponse("Response", GlassesReadability())

        assertEquals(thinking[0].documentKey, thinkingUpdate[0].documentKey)
        assertEquals(messageId(thinking[0]), messageId(thinkingUpdate[1]))
        assertEquals("Thinking", sourceText(thinking[1]))
        assertEquals("Thinking\n\n• read_file — Running", sourceText(thinkingUpdate[1]))
        assertNotEquals(thinking[0].documentKey, response[0].documentKey)
        assertNotEquals(messageId(thinking[0]), messageId(response[0]))
    }

    @Test
    fun thinkingReusesActiveInputIdentityAndKeepsInputPrefixOnceAcrossToolUpdates() {
        var documentNumber = 0
        val renderer = MyvuDisplayRenderer(documentId = { "document-${++documentNumber}" })

        val input = renderer.commandsFor("Transcribed question", DisplayKind.Input)
        val thinking = renderer.openThinking("Thinking", GlassesReadability())
        val toolUpdate = renderer.updateThinking("Thinking\n\n• read_file — Running")
        val response = renderer.openResponse("Response", GlassesReadability())
        val nextThinking = renderer.openThinking("Thinking", GlassesReadability())

        assertEquals(input[0].documentKey, thinking[0].documentKey)
        assertEquals(thinking[0].documentKey, toolUpdate[0].documentKey)
        assertEquals(messageId(input[0]), messageId(thinking[0]))
        assertEquals(messageId(thinking[0]), messageId(toolUpdate[1]))
        assertEquals("Transcribed question\n\nThinking", sourceText(thinking[1]))
        assertEquals(
            "Transcribed question\n\nThinking\n\n• read_file — Running",
            sourceText(toolUpdate[1]),
        )
        assertNotEquals(thinking[0].documentKey, response[0].documentKey)
        assertNotEquals(messageId(thinking[0]), messageId(response[0]))
        assertNotEquals(response[0].documentKey, nextThinking[0].documentKey)
        assertEquals("Thinking", sourceText(nextThinking[1]))
    }

    @Test
    fun phone_input_reuses_active_document_and_keeps_only_visible_text() {
        val renderer = MyvuDisplayRenderer(documentId = { "doc" })

        val context = renderer.commandsFor("Context", DisplayKind.Context)
        val input = renderer.commandsFor("Visible attachment prompt", DisplayKind.Input)

        assertEquals(context[0].documentKey, input[0].documentKey)
        assertTrue(input[1].payload.contains("Visible attachment prompt"))
    }

    @Test
    fun readabilityPolicyKeepsTextAndUsesSelectedFontAndPacing() {
        val renderer = MyvuDisplayRenderer(documentId = { "doc" })

        val commands =
            renderer.commandsFor(
                text = "A long response must be projected in full.",
                kind = DisplayKind.Response,
                readability = GlassesReadability(fontMode = GlassesFontMode.Large, pacingMillis = 450),
            )

        assertTrue(commands[0].payload.contains("\\\"ticiSpeed\\\":450"))
        assertEquals(GlassesFontMode.Large, commands.fontCommand?.fontMode)
        assertTrue(commands[1].payload.contains("A long response must be projected in full."))
    }

    @Test
    fun responseUpdateReopensActiveDocumentThenSendsContentWithoutChangingFont() {
        val renderer = MyvuDisplayRenderer(documentId = { "doc" })
        val opened =
            renderer.commandsFor(
                text = "Partial",
                kind = DisplayKind.Response,
                readability = GlassesReadability(fontMode = GlassesFontMode.Large, pacingMillis = 450),
            )
        renderer.commandsFor(
            text = "Working",
            kind = DisplayKind.Status,
            readability = GlassesReadability(pacingMillis = 200),
        )

        val update = renderer.updateResponse("Partial answer")

        assertEquals(2, update.size)
        assertEquals(opened[0].documentKey, update[0].documentKey)
        assertEquals(update[0].documentKey, update[1].documentKey)
        assertEquals(messageId(opened[0]), messageId(update[0]))
        assertEquals(messageId(update[0]), messageId(update[1]))
        assertEquals(
            "open_app",
            Json.parseToJsonElement(update[0].payload).jsonObject["data"]!!.jsonObject["action"]
                .toString()
                .trim('"'),
        )
        assertEquals(
            "send_content",
            Json.parseToJsonElement(update[1].payload).jsonObject["data"]!!.jsonObject["action"]
                .toString()
                .trim('"'),
        )
        assertTrue(update[0].payload.contains("\\\"fileKey\\\":\\\"doc/hermes-agent\\\""))
        assertTrue(update[0].payload.contains("\\\"ticiSpeed\\\":450"))
        assertTrue(update[1].payload.contains("\\\"fileKey\\\":\\\"doc/hermes-agent\\\""))
        assertTrue(update[1].payload.contains("Partial answer"))
        assertTrue(update.none { it.fontMode != null })
    }

    private fun sourceText(command: MyvuDisplayCommand): String {
        val data = Json.parseToJsonElement(command.payload).jsonObject["data"]!!.jsonObject
        return Json
            .parseToJsonElement(data["value"]!!.jsonPrimitive.content)
            .jsonObject["sourceText"]!!
            .jsonPrimitive.content
    }

    private fun messageId(command: MyvuDisplayCommand): String {
        val outer = Json.parseToJsonElement(command.payload).jsonObject
        val data = outer["data"]!!.jsonObject
        val payload =
            if (data["action"]!!.jsonPrimitive.content == "open_app") {
                data["ext"]!!.jsonPrimitive.content
            } else {
                data["value"]!!.jsonPrimitive.content
            }
        return Json.parseToJsonElement(payload).jsonObject["msgId"]!!.jsonPrimitive.content
    }
}
