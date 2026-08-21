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
    fun responseUpdateSendsOnlyContentToTheActiveDocument() {
        val renderer = MyvuDisplayRenderer(documentId = { "doc" })
        val opened = renderer.commandsFor("Partial", DisplayKind.Response)

        val update = renderer.updateResponse("Partial answer")

        assertEquals(1, update.size)
        assertEquals(opened.single { it.payload.contains("send_content") }.documentKey, update.single().documentKey)
        assertTrue(update.single().payload.contains("Partial answer"))
    }
}
