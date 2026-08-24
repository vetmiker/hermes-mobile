package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.myvu.GlassesFontMode
import com.m57.hermescontrol.glasses.myvu.GlassesReadability
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayCommand
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class MyvuTurnStreamPublisherTest {
    @Test
    fun startEpochPublishesExactThinkingBeforeTokensAndTools() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            runCurrent()

            publisher.publishToken("Buffered")
            advanceTimeBy(200)
            publisher.publishToolStart("read_file", mapOf("path" to "/tmp/x"))
            advanceUntilIdle()

            val texts = visibleTexts(commands)
            assertEquals("Thinking", texts.first())
            assertFalse(texts[1].contains("Buffered"))
            assertTrue(texts[1].startsWith("Thinking\n\n• read_file: /tmp/x — Starting"))
            assertEquals(listOf("open_app", "send_content", "set_font_mode"), commandActions(commands).take(3))
            publisher.close()
        }

    @Test
    fun tokensStayOffScreenUntilThePagePlusMarginThreshold() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            runCurrent()

            publisher.publishToken(
                textWithLines(
                    MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard) - 1,
                ),
            )
            advanceTimeBy(200)
            advanceUntilIdle()

            assertEquals(listOf("Thinking"), visibleTexts(commands))
            assertEquals(1, commands.map { it.documentKey }.distinct().size)
            publisher.close()
        }

    @Test
    fun thresholdOpensOneResponseWithAllBufferedText() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val documentIds = AtomicInteger()
            val publisher =
                publisher(
                    commands = commands,
                    dispatcher = StandardTestDispatcher(testScheduler),
                    documentId = { "document-${documentIds.incrementAndGet()}" },
                )
            val thresholdText =
                textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
            publisher.startEpoch()
            runCurrent()

            publisher.publishToken(thresholdText.take(10))
            publisher.publishToken(thresholdText.drop(10))
            advanceUntilIdle()

            assertEquals(listOf("Thinking", thresholdText), visibleTexts(commands))
            assertEquals(
                listOf("document-1/hermes-agent", "document-2/hermes-agent"),
                contentDocumentKeys(commands),
            )
            publisher.close()
        }

    @Test
    fun blockedWriterKeepsThinkingBeforeImmediateThresholdAndFinalResponse() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        val thresholdText =
            textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
        publisher.startEpoch()
        publisher.publishToken(thresholdText)
        publisher.publishFinal("Authoritative final")

        dispatcher.release()

        assertEquals(listOf("Thinking", "Authoritative final"), visibleTexts(commands))
        assertEquals(2, commands.map { it.documentKey }.distinct().size)
        assertEquals(
            listOf(
                "open_app",
                "send_content",
                "set_font_mode",
                "open_app",
                "send_content",
                "set_font_mode",
            ),
            commandActions(commands),
        )
        publisher.close()
    }

    @Test
    fun thresholdTokenDuringThinkingDeliveryWaitsForThinkingThenOpensResponse() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val thresholdText =
                textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
            var documentNumber = 0
            lateinit var publisher: MyvuTurnStreamPublisher
            publisher =
                MyvuTurnStreamPublisher(
                    renderer = MyvuDisplayRenderer(documentId = { "document-${++documentNumber}" }),
                    readability = { GlassesReadability() },
                    writer =
                        MyvuCommandWriter { command ->
                            commands += command
                            if (commands.size == 1) publisher.publishToken(thresholdText)
                        },
                    writerDispatcher = StandardTestDispatcher(testScheduler),
                )
            publisher.startEpoch()
            advanceUntilIdle()

            assertEquals(listOf("Thinking", thresholdText), visibleTexts(commands))
            assertEquals(2, commands.map { it.documentKey }.distinct().size)
            publisher.close()
        }

    @Test
    fun partialToolAndFinalUpdatesKeepTheOneResponseIdentity() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            val thresholdText =
                textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
            publisher.startEpoch()
            runCurrent()
            publisher.publishToken(thresholdText)
            advanceUntilIdle()

            publisher.publishToken(" tail")
            advanceTimeBy(200)
            advanceUntilIdle()
            publisher.publishToolStart("read_file", mapOf("path" to "/tmp/x"))
            advanceUntilIdle()
            publisher.publishFinal("Final")
            advanceUntilIdle()

            val responseCommands = contentCommands(commands).drop(1)
            assertEquals(1, responseCommands.map { it.documentKey }.distinct().size)
            assertEquals(1, responseCommands.map(::messageId).distinct().size)
            assertEquals(
                listOf(
                    thresholdText,
                    "$thresholdText tail",
                    "$thresholdText tail\n\n• read_file: /tmp/x — Starting",
                    "Final",
                ),
                visibleTexts(commands).drop(1),
            )
            assertEquals(2, commands.map { it.documentKey }.distinct().size)
            publisher.close()
        }

    @Test
    fun shortFinalOpensOneResponseAndNeverMintsAThirdIdentity() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            runCurrent()

            publisher.publishToken("Buffered")
            publisher.publishFinal("Short final")
            advanceUntilIdle()

            assertEquals(listOf("Thinking", "Short final"), visibleTexts(commands))
            assertEquals(2, commands.map { it.documentKey }.distinct().size)
            assertEquals(1, contentCommands(commands).drop(1).map(::messageId).distinct().size)
            publisher.close()
        }

    @Test
    fun completeOnlyFinalKeepsThinkingThenOpensOneResponse() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            runCurrent()

            publisher.publishFinal("Complete response")
            advanceUntilIdle()

            assertEquals(listOf("Thinking", "Complete response"), visibleTexts(commands))
            assertEquals(2, commands.map { it.documentKey }.distinct().size)
            publisher.close()
        }

    @Test
    fun finalFencesQueuedResponseButPreservesThinking() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        val thresholdText =
            textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
        publisher.startEpoch()
        publisher.publishToken(thresholdText)
        publisher.publishFinal("Final")

        dispatcher.release()

        assertEquals(listOf("Thinking", "Final"), visibleTexts(commands))
        assertEquals(2, contentCommands(commands).size)
        publisher.close()
    }

    @Test
    fun newEpochAndCloseSuppressStaleQueuedWork() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        publisher.startEpoch()
        publisher.publishToken(
            textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard)),
        )
        publisher.startEpoch()
        publisher.publishFinal("Fresh")
        dispatcher.release()

        assertEquals(listOf("Thinking", "Fresh"), visibleTexts(commands))
        publisher.close()
        assertEquals(listOf("Thinking", "Fresh"), visibleTexts(commands))

        val closedCommands = mutableListOf<MyvuDisplayCommand>()
        val closeDispatcher = BlockingWriterDispatcher()
        val closedPublisher = publisher(closedCommands, closeDispatcher)
        closedPublisher.startEpoch()
        closedPublisher.publishToken(
            textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard)),
        )
        closedPublisher.close()
        closeDispatcher.release()
        assertTrue(closedCommands.isEmpty())
    }

    @Test
    fun newEpochAfterResponseOpenCompletesPairAndSuppressesFinalCallback() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val delivered = AtomicInteger()
            val opened = AtomicInteger()
            lateinit var publisher: MyvuTurnStreamPublisher
            publisher =
                MyvuTurnStreamPublisher(
                    renderer = MyvuDisplayRenderer(),
                    readability = { GlassesReadability() },
                    writer =
                        MyvuCommandWriter { command ->
                            commands += command
                            if (
                                commandActions(listOf(command)) == listOf("open_app") &&
                                opened.incrementAndGet() == 2
                            ) {
                                publisher.startEpoch()
                            }
                        },
                    writerDispatcher = StandardTestDispatcher(testScheduler),
                )
            publisher.startEpoch()
            runCurrent()

            publisher.publishFinal("Final") { delivered.incrementAndGet() }
            advanceUntilIdle()

            assertEquals(listOf("Thinking", "Final", "Thinking"), visibleTexts(commands))
            assertEquals(0, delivered.get())
            assertEquals(
                commands.count { commandActions(listOf(it)) == listOf("open_app") },
                commands.count { commandActions(listOf(it)) == listOf("send_content") },
            )
            publisher.close()
        }

    @Test
    fun closeAfterResponseOpenCompletesPairAndSuppressesFinalCallback() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val delivered = AtomicInteger()
            val opened = AtomicInteger()
            lateinit var publisher: MyvuTurnStreamPublisher
            publisher =
                MyvuTurnStreamPublisher(
                    renderer = MyvuDisplayRenderer(),
                    readability = { GlassesReadability() },
                    writer =
                        MyvuCommandWriter { command ->
                            commands += command
                            if (
                                commandActions(listOf(command)) == listOf("open_app") &&
                                opened.incrementAndGet() == 2
                            ) {
                                publisher.close()
                            }
                        },
                    writerDispatcher = StandardTestDispatcher(testScheduler),
                )
            publisher.startEpoch()
            runCurrent()

            publisher.publishFinal("Final") { delivered.incrementAndGet() }
            advanceUntilIdle()

            assertEquals(listOf("Thinking", "Final"), visibleTexts(commands))
            assertEquals(0, delivered.get())
            assertEquals(
                commands.count { commandActions(listOf(it)) == listOf("open_app") },
                commands.count { commandActions(listOf(it)) == listOf("send_content") },
            )
        }

    @Test
    fun everyAcceptedVisibleUpdateUsesTheOpenContentPair() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            val surface = ContentGatedSurface()
            val thresholdText =
                textWithLines(MyvuResponsePageLayout.pagePlusOneLineCapacity(GlassesFontMode.Standard))
            publisher.startEpoch()
            runCurrent()
            publisher.publishToolStart("read_file", mapOf("path" to "/tmp/x"))
            publisher.publishToken(thresholdText)
            publisher.publishToken(" tail")
            advanceTimeBy(200)
            advanceUntilIdle()
            publisher.publishFinal("Final")
            advanceUntilIdle()

            commands.forEach(surface::receive)

            assertEquals(visibleTexts(commands), surface.visibleTexts)
            assertTrue(commandActions(commands).windowed(2).all { it != listOf("open_app", "set_font_mode") })
            assertEquals(
                commands.count { commandActions(listOf(it)) == listOf("open_app") },
                commands.count { commandActions(listOf(it)) == listOf("send_content") },
            )
            publisher.close()
        }

    @Test
    fun overflowEstimatorCountsExplicitLinesAndConservativeWordWrapping() {
        val fontMode = GlassesFontMode.Standard
        val columns = MyvuResponsePageLayout.columnsPerLine(fontMode)

        assertEquals(2, MyvuResponsePageLayout.estimatedWrappedLines("x".repeat(columns + 1), fontMode))
        assertEquals(3, MyvuResponsePageLayout.estimatedWrappedLines("x\nx\n", fontMode))
    }

    private fun publisher(
        commands: MutableList<MyvuDisplayCommand>,
        dispatcher: CoroutineDispatcher,
        readability: () -> GlassesReadability = { GlassesReadability() },
        documentId: (() -> String)? = null,
    ): MyvuTurnStreamPublisher =
        MyvuTurnStreamPublisher(
            renderer =
                documentId?.let { MyvuDisplayRenderer(documentId = it) }
                    ?: MyvuDisplayRenderer(),
            readability = readability,
            writer = MyvuCommandWriter { commands += it },
            writerDispatcher = dispatcher,
        )

    private fun textWithLines(lines: Int): String = List(lines) { "x" }.joinToString("\n")

    private fun contentCommands(commands: List<MyvuDisplayCommand>): List<MyvuDisplayCommand> =
        commands.filter { it.payload.contains("send_content") }

    private fun contentDocumentKeys(commands: List<MyvuDisplayCommand>): List<String> =
        contentCommands(commands).map { it.documentKey }

    private fun visibleTexts(commands: List<MyvuDisplayCommand>): List<String> =
        contentCommands(commands).map(::sourceText)

    private fun sourceText(command: MyvuDisplayCommand): String {
        val outer = Json.parseToJsonElement(command.payload).jsonObject
        val content = Json.parseToJsonElement(outer["data"]!!.jsonObject["value"]!!.jsonPrimitive.content).jsonObject
        return content["sourceText"]!!.jsonPrimitive.content
    }

    private fun messageId(command: MyvuDisplayCommand): String {
        val outer = Json.parseToJsonElement(command.payload).jsonObject
        val content = Json.parseToJsonElement(outer["data"]!!.jsonObject["value"]!!.jsonPrimitive.content).jsonObject
        return content["msgId"]!!.jsonPrimitive.content
    }

    private fun commandActions(commands: List<MyvuDisplayCommand>): List<String> =
        commands.map { command ->
            Json
                .parseToJsonElement(command.payload)
                .jsonObject["data"]!!
                .jsonObject["action"]!!
                .jsonPrimitive.content
        }

    private class ContentGatedSurface {
        private var nextContentDocumentKey: String? = null

        val visibleTexts = mutableListOf<String>()

        fun receive(command: MyvuDisplayCommand) {
            val data = Json.parseToJsonElement(command.payload).jsonObject["data"]!!.jsonObject
            when (data["action"]!!.jsonPrimitive.content) {
                "open_app" -> {
                    val ext = Json.parseToJsonElement(data["ext"]!!.jsonPrimitive.content).jsonObject
                    nextContentDocumentKey = ext["fileKey"]!!.jsonPrimitive.content
                }

                "send_content" -> {
                    val content = Json.parseToJsonElement(data["value"]!!.jsonPrimitive.content).jsonObject
                    if (content["fileKey"]!!.jsonPrimitive.content == nextContentDocumentKey) {
                        nextContentDocumentKey = null
                        visibleTexts += content["sourceText"]!!.jsonPrimitive.content
                    }
                }
            }
        }
    }

    private class BlockingWriterDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            tasks.addLast(block)
        }

        fun release() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
