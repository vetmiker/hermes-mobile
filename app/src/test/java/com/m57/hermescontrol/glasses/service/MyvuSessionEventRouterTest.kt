package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.glasses.ChatTurnCoordinator
import com.m57.hermescontrol.glasses.GlassesModeSnapshot
import com.m57.hermescontrol.glasses.GlassesModeState
import com.m57.hermescontrol.glasses.TurnGateway
import com.m57.hermescontrol.glasses.TurnRequest
import com.m57.hermescontrol.glasses.TurnSource
import com.m57.hermescontrol.glasses.TurnStore
import com.m57.hermescontrol.glasses.myvu.GlassesReadability
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayCommand
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class MyvuSessionEventRouterTest {
    @Test
    fun routesOnlyOrderedMatchingEpochAndCompletesAfterFinalDelivery() {
        val publisher = FakePublisher()
        val completed = mutableListOf<String>()
        val snapshot = activeSnapshot()
        val router = MyvuSessionEventRouter(publisher, { snapshot }) { _, text -> completed += text }

        router.route(WsEvent.MessageToken("ignored", "runtime"))
        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.MessageToken("Hello", "runtime"))
        router.route(WsEvent.ToolStart("read_file", mapOf("path" to "/tmp/x"), "runtime"))
        router.route(WsEvent.MessageComplete("Final", "runtime"))
        router.route(WsEvent.MessageToken("late", "runtime"))

        assertEquals(listOf("start", "token:Hello", "tool:start:read_file", "final:Final"), publisher.calls)
        assertEquals(listOf("Final"), completed)
    }

    @Test
    fun otherSessionAndInactiveStateEventsAreInert() {
        val publisher = FakePublisher()
        val router = MyvuSessionEventRouter(publisher, { activeSnapshot() }) { _, _ -> }

        router.route(WsEvent.MessageStart("other"))
        router.route(WsEvent.MessageToken("ignored", "other"))

        assertTrue(publisher.calls.isEmpty())
    }

    @Test
    fun completeOnlyFallbackUsesTheMessageStartEpoch() {
        val publisher = FakePublisher()
        val router = MyvuSessionEventRouter(publisher, { activeSnapshot() }) { _, _ -> }

        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.MessageComplete("Final without tokens", "runtime"))

        assertEquals(listOf("start", "final:Final without tokens"), publisher.calls)
    }

    @Test
    fun replacedControllerGenerationMakesPriorEpochInert() {
        val publisher = FakePublisher()
        var snapshot = activeSnapshot()
        val router = MyvuSessionEventRouter(publisher, { snapshot }) { _, _ -> }

        router.route(WsEvent.MessageStart("runtime"))
        snapshot = snapshot.copy(generation = snapshot.generation + 1)
        router.route(WsEvent.MessageToken("stale", "runtime"))

        assertEquals(listOf("start"), publisher.calls)
    }

    @Test
    fun routesNullSessionFollowUpsThroughTheOpenEpochAndClosesAfterFinal() {
        val publisher = FakePublisher()
        val completed = mutableListOf<String>()
        val router = MyvuSessionEventRouter(publisher, ::activeSnapshot) { _, text -> completed += text }

        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.ToolStart("read_file", mapOf("path" to "/tmp/x")))
        router.route(WsEvent.ToolGenerating("read_file"))
        router.route(WsEvent.ToolProgress("read_file", "Reading"))
        router.route(WsEvent.ToolComplete("read_file", emptyMap()))
        router.route(WsEvent.MessageToken("Hello", null))
        router.route(WsEvent.MessageComplete("Final", null))
        router.route(WsEvent.MessageToken("late", null))

        assertEquals(
            listOf(
                "start",
                "tool:start:read_file",
                "tool:generating:read_file",
                "tool:progress:read_file:Reading",
                "tool:complete:read_file",
                "token:Hello",
                "final:Final",
            ),
            publisher.calls,
        )
        assertEquals(listOf("Final"), completed)
    }

    @Test
    fun closeIsTerminalForExactStartsAndNullSessionFollowUps() {
        val publisher = FakePublisher()
        val completed = mutableListOf<String>()
        val router = MyvuSessionEventRouter(publisher, ::activeSnapshot) { _, text -> completed += text }

        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.MessageToken("before-close", null))
        router.close()
        router.close()
        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.ToolStart("read_file", null))
        router.route(WsEvent.MessageToken("after-close", null))
        router.route(WsEvent.MessageComplete("after-close", null))

        assertEquals(
            listOf(
                "start",
                "token:before-close",
                "close",
            ),
            publisher.calls,
        )
        assertTrue(completed.isEmpty())
    }

    @Test
    fun fencesConflictingSessionEventsAndNullEventsBeforeMessageStart() {
        val publisher = FakePublisher()
        val router = MyvuSessionEventRouter(publisher, ::activeSnapshot) { _, _ -> }

        router.route(WsEvent.ToolStart("before-start", null))
        router.route(WsEvent.MessageToken("before-start", null))
        router.route(WsEvent.MessageComplete("before-start", null))
        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.ToolStart("other", null, "other"))
        router.route(WsEvent.MessageToken("other", "other"))
        router.route(WsEvent.MessageComplete("other", "other"))

        assertEquals(listOf("start"), publisher.calls)
    }

    @Test
    fun blockedFinalDeliveryKeepsVoiceLeaseUntilWriterCompletes() {
        val coordinator = coordinator()
        val lease =
            runBlocking {
                coordinator.submit(TurnRequest("stored", "runtime", "voice", TurnSource.VOICE)).lease!!
            }
        val dispatcher = BlockingWriterDispatcher()
        val router =
            MyvuSessionEventRouter(
                publisher = publisher(dispatcher),
                currentSnapshot = ::activeSnapshot,
                onFinalDelivered = { _, text ->
                    runBlocking { coordinator.completeTerminal(lease, "runtime", text) }
                },
            )

        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.MessageComplete("Final", "runtime"))

        assertFalse(
            runBlocking { coordinator.submit(TurnRequest("stored", "other", "blocked", TurnSource.PHONE)).accepted },
        )
        dispatcher.release()
        assertTrue(
            runBlocking { coordinator.submit(TurnRequest("stored", "other", "next", TurnSource.PHONE)).accepted },
        )
        router.close()
    }

    @Test
    fun blockedFinalDeliveryKeepsPhonePriorityLeaseUntilWriterCompletes() {
        val coordinator = coordinator()
        runBlocking { coordinator.submit(TurnRequest("stored", "runtime", "phone", TurnSource.PHONE)) }
        val dispatcher = BlockingWriterDispatcher()
        val snapshot = activeSnapshot().copy(state = GlassesModeState.PHONE_PRIORITY)
        val router =
            MyvuSessionEventRouter(
                publisher = publisher(dispatcher),
                currentSnapshot = { snapshot },
                onFinalDelivered = { _, text ->
                    runBlocking { coordinator.completeTerminalForRuntime("runtime", text) }
                },
            )

        router.route(WsEvent.MessageStart("runtime"))
        router.route(WsEvent.MessageComplete("Final", "runtime"))

        assertFalse(
            runBlocking { coordinator.submit(TurnRequest("stored", "other", "blocked", TurnSource.PHONE)).accepted },
        )
        dispatcher.release()
        assertTrue(
            runBlocking { coordinator.submit(TurnRequest("stored", "other", "next", TurnSource.PHONE)).accepted },
        )
        router.close()
    }

    private fun publisher(dispatcher: CoroutineDispatcher): MyvuTurnStreamPublisher =
        MyvuTurnStreamPublisher(
            renderer = MyvuDisplayRenderer(documentId = { "document" }),
            readability = { GlassesReadability() },
            writer = MyvuCommandWriter { _: MyvuDisplayCommand -> },
            writerDispatcher = dispatcher,
        )

    private fun coordinator() =
        ChatTurnCoordinator(
            gateway =
                object : TurnGateway {
                    override suspend fun submit(
                        runtimeSessionId: String,
                        text: String,
                    ) = Unit

                    override suspend fun redirect(
                        runtimeSessionId: String,
                        text: String,
                    ) = Unit
                },
            store =
                object : TurnStore {
                    override suspend fun persist(
                        storedSessionId: String,
                        text: String,
                    ) = Unit
                },
        )

    private fun activeSnapshot() =
        GlassesModeSnapshot(
            generation = 7,
            storedSessionId = "stored",
            runtimeSessionId = "runtime",
            state = GlassesModeState.AWAITING_HERMES,
        )

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

    private class FakePublisher : MyvuTurnPublisher {
        val calls = mutableListOf<String>()

        override fun startEpoch() {
            calls += "start"
        }

        override fun publishToken(token: String) {
            calls += "token:$token"
        }

        override fun publishToolStart(
            name: String?,
            data: Map<String, Any?>?,
        ) {
            calls += "tool:start:$name"
        }

        override fun publishToolGenerating(name: String?) {
            calls += "tool:generating:$name"
        }

        override fun publishToolProgress(
            name: String?,
            preview: String?,
        ) {
            calls += "tool:progress:$name:$preview"
        }

        override fun publishToolComplete(name: String?) {
            calls += "tool:complete:$name"
        }

        override fun publishToolRisk(name: String?) = Unit

        override fun publishFinal(
            text: String,
            afterDelivery: (() -> Unit)?,
        ) {
            calls += "final:$text"
            afterDelivery?.invoke()
        }

        override fun close() {
            calls += "close"
        }
    }
}
