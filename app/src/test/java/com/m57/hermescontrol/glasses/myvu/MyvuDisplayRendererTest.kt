package com.m57.hermescontrol.glasses.myvu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
}
