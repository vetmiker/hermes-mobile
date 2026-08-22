package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.myvu.GlassesReadability
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayCommand
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
    fun tokensOpenOneDocumentThenCoalesceCumulativeUpdates() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()

            publisher.publishToken("Hello")
            runCurrent()
            publisher.publishToken(", ")
            publisher.publishToken("world")
            advanceTimeBy(199)
            runCurrent()

            assertEquals(listOf("Hello"), visibleTexts(commands))

            advanceTimeBy(1)
            advanceUntilIdle()

            assertEquals(listOf("Hello", "Hello, world"), visibleTexts(commands))
            assertEquals(1, commands.map { it.documentKey }.distinct().size)
            publisher.close()
        }

    @Test
    fun contentGatedSurfaceAcceptsFirstUpdateAndFinalPublisherSequence() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            val surface = ContentGatedSurface()
            publisher.startEpoch()

            publisher.publishToken("First")
            runCurrent()
            publisher.publishToken(" update")
            advanceTimeBy(200)
            advanceUntilIdle()
            publisher.publishFinal("Final")
            advanceUntilIdle()

            commands.forEach(surface::receive)

            assertEquals(listOf("First", "First update", "Final"), surface.visibleTexts)
            publisher.close()
        }

    @Test
    fun finalDropsQueuedPartialAndIsTheLastWrite() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            publisher.publishToken("Partial")
            runCurrent()
            publisher.publishToken(" ignored")

            publisher.publishFinal("Authoritative final")
            advanceUntilIdle()

            assertEquals(listOf("Partial", "Authoritative final"), visibleTexts(commands))
            publisher.close()
        }

    @Test
    fun finalReplacesAnUnconsumedPartialAndOpensItsOwnDocument() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        val delivered = AtomicInteger()
        publisher.startEpoch()

        publisher.publishToken("Partial")
        publisher.publishFinal("Authoritative final") { delivered.incrementAndGet() }

        assertTrue(commands.isEmpty())
        dispatcher.release()

        assertEquals(listOf("Authoritative final"), visibleTexts(commands))
        assertEquals(1, commands.count { it.payload.contains("open_app") })
        assertEquals(1, delivered.get())
        publisher.close()
    }

    @Test
    fun blockedWriterPreservesToolTransitionsInFifoOrder() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        publisher.startEpoch()

        publisher.publishToolStart("read_file", mapOf("path" to "/tmp/x"))
        publisher.publishToolGenerating("read_file")
        publisher.publishToolProgress("read_file", "untrusted preview")
        publisher.publishToolComplete("read_file")
        publisher.publishToolRisk("read_file")

        dispatcher.release()

        val texts = visibleTexts(commands)
        assertEquals(5, texts.size)
        assertTrue(texts[0].contains("Starting"))
        assertTrue(texts[1].contains("Preparing"))
        assertTrue(texts[2].contains("Running"))
        assertTrue(texts[3].contains("Completed"))
        assertTrue(texts[4].contains("redacted"))
        publisher.close()
    }

    @Test
    fun finalDiscardsPendingPartialButFollowsQueuedToolTransition() {
        val commands = mutableListOf<MyvuDisplayCommand>()
        val dispatcher = BlockingWriterDispatcher()
        val publisher = publisher(commands, dispatcher)
        publisher.startEpoch()

        publisher.publishToken("Partial")
        publisher.publishToolStart("read_file", mapOf("path" to "/tmp/x"))
        publisher.publishFinal("Final")

        dispatcher.release()

        val texts = visibleTexts(commands)
        assertEquals(2, texts.size)
        assertTrue(texts.single { it.contains("Starting") }.contains("/tmp/x"))
        assertEquals("Final", texts.last())
        publisher.close()
    }

    @Test
    fun finalFencesTheScheduledPartialUpdate() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            publisher.publishToken("First")
            runCurrent()
            publisher.publishToken(" stale")

            publisher.publishFinal("Final")
            advanceTimeBy(200)
            advanceUntilIdle()

            assertEquals(listOf("First", "Final"), visibleTexts(commands))
            publisher.close()
        }

    @Test
    fun toolDetailsAreAllowlistedAndProgressNeverExposesPreview() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()

            publisher.publishToolStart("read_file", mapOf("path" to "/safe/path", "token" to "secret"))
            advanceUntilIdle()
            assertTrue(visibleTexts(commands).last().contains("/safe/path"))

            publisher.publishToolProgress("read_file", "api-key=leaked")
            advanceUntilIdle()

            val text = visibleTexts(commands).last()
            assertTrue(text.contains("📄 read_file"))
            assertTrue(text.contains("Running"))
            assertFalse(text.contains("api-key"))
            assertFalse(text.contains("secret"))
            publisher.close()
        }

    @Test
    fun terminalAndUnknownToolArgumentsNeverReachGlasses() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()

            publisher.publishToolStart("terminal", mapOf("command" to "cat ~/.ssh/id_rsa"))
            publisher.publishToolStart("unknown", mapOf("value" to "private value"))
            advanceUntilIdle()

            val text = visibleTexts(commands).last()
            assertFalse(text.contains("id_rsa"))
            assertFalse(text.contains("private value"))
            publisher.close()
        }

    @Test
    fun closeMakesPendingAndLaterWritesInert() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()
            publisher.publishToken("Partial")
            publisher.close()
            advanceUntilIdle()

            assertTrue(commands.isEmpty())
        }

    @Test
    fun staleQueuedGenerationNeverRenders() =
        runTest {
            val commands = mutableListOf<MyvuDisplayCommand>()
            val publisher = publisher(commands, StandardTestDispatcher(testScheduler))
            publisher.startEpoch()

            publisher.enqueueStaleIntent("stale")
            runCurrent()

            assertTrue(commands.isEmpty())
            publisher.close()
        }

    @Suppress("UNCHECKED_CAST")
    private fun MyvuTurnStreamPublisher.enqueueStaleIntent(text: String) {
        val publisherClass = javaClass
        val generation =
            publisherClass
                .getDeclaredField("generation")
                .apply { isAccessible = true }
                .getLong(this)
        val intentClass = publisherClass.declaredClasses.single { it.simpleName == "RenderIntent" }
        val staleIntent =
            intentClass.declaredConstructors
                .single { it.parameterTypes.size == 5 }
                .apply { isAccessible = true }
                .newInstance(generation - 1, text, false, false, null)
        val intents =
            publisherClass
                .getDeclaredField("intents")
                .apply { isAccessible = true }
                .get(this) as kotlin.collections.ArrayDeque<Any>
        val wakeups =
            publisherClass
                .getDeclaredField("writerWakeups")
                .apply { isAccessible = true }
                .get(this) as Channel<Unit>

        intents.addLast(staleIntent)
        wakeups.trySend(Unit).getOrThrow()
    }

    private fun publisher(
        commands: MutableList<MyvuDisplayCommand>,
        dispatcher: CoroutineDispatcher,
    ): MyvuTurnStreamPublisher =
        MyvuTurnStreamPublisher(
            renderer = MyvuDisplayRenderer(documentId = { "document" }),
            readability = { GlassesReadability() },
            writer = MyvuCommandWriter { commands += it },
            writerDispatcher = dispatcher,
        )

    private fun visibleTexts(commands: List<MyvuDisplayCommand>): List<String> =
        commands
            .filter { it.payload.contains("send_content") }
            .map { command ->
                val outer = Json.parseToJsonElement(command.payload).jsonObject
                val inner =
                    Json.parseToJsonElement(
                        outer["data"]!!.jsonObject["value"]!!.jsonPrimitive.content,
                    ).jsonObject
                inner["sourceText"]!!.jsonPrimitive.content
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
