package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.myvu.DisplayKind
import com.m57.hermescontrol.glasses.myvu.GlassesReadability
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayCommand
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import com.m57.hermescontrol.ui.chat.ToolSchemaRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun interface MyvuCommandWriter {
    fun send(command: MyvuDisplayCommand)
}

internal interface MyvuTurnPublisher {
    fun startEpoch()

    fun publishToken(token: String)

    fun publishToolStart(
        name: String?,
        data: Map<String, Any?>?,
    )

    fun publishToolGenerating(name: String?)

    fun publishToolProgress(
        name: String?,
        preview: String?,
    )

    fun publishToolComplete(name: String?)

    fun publishToolRisk(name: String?)

    fun publishFinal(
        text: String,
        afterDelivery: (() -> Unit)? = null,
    )

    fun close()
}

/**
 * Session-local, ordered projection of one assistant response onto MYVU.
 *
 * Event callbacks only update the projection and queue immutable work. Binder
 * calls are serialized on [writerDispatcher], and ordinary token updates are
 * coalesced to a 200 ms cadence.
 */
internal class MyvuTurnStreamPublisher(
    private val renderer: MyvuDisplayRenderer,
    private val readability: () -> GlassesReadability,
    private val writer: MyvuCommandWriter,
    writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MyvuTurnPublisher {
    private data class RenderIntent(
        val generation: Long,
        val text: String,
        val isPartial: Boolean = false,
        val isFinal: Boolean = false,
        val afterDelivery: (() -> Unit)? = null,
    )

    private val stateLock = Any()
    private val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
    private val intents = ArrayDeque<RenderIntent>()
    private val writerWakeups = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    private var epochOpen = false
    private var finalQueued = false
    private val assistantText = StringBuilder()
    private var toolLine: String? = null
    private var projectionQueued = false
    private var pendingPartial: Job? = null

    init {
        writerScope.launch {
            var openedGeneration: Long? = null
            for (ignored in writerWakeups) {
                while (true) {
                    val intent =
                        synchronized(stateLock) {
                            if (intents.isEmpty()) null else intents.removeFirst()
                        } ?: break
                    val shouldRender =
                        synchronized(stateLock) {
                            intent.generation == generation && epochOpen
                        }
                    if (!shouldRender) continue

                    val opensDocument = openedGeneration != intent.generation
                    val commands =
                        if (opensDocument) {
                            renderer.commandsFor(intent.text, DisplayKind.Response, readability())
                        } else {
                            renderer.updateResponse(intent.text)
                        }
                    commands.forEach(writer::send)
                    if (opensDocument) openedGeneration = intent.generation

                    val shouldDeliverFinal =
                        intent.isFinal &&
                            synchronized(stateLock) {
                                intent.generation == generation && epochOpen
                            }
                    if (shouldDeliverFinal) intent.afterDelivery?.invoke()
                }
            }
        }
    }

    override fun startEpoch() {
        synchronized(stateLock) {
            generation += 1
            epochOpen = true
            finalQueued = false
            assistantText.clear()
            toolLine = null
            projectionQueued = false
            cancelPendingPartialLocked()
            intents.clear()
        }
    }

    override fun publishToken(token: String) {
        if (token.isEmpty()) return
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            assistantText.append(token)
            toolLine = null
            if (!projectionQueued) {
                enqueuePartialLocked()
                projectionQueued = true
            } else {
                schedulePartialLocked()
            }
        }
    }

    override fun publishToolStart(
        name: String?,
        data: Map<String, Any?>?,
    ) {
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            cancelPendingPartialLocked()
            toolLine = formatTool(name, data, "Starting")
            enqueueCurrentLocked()
        }
    }

    override fun publishToolGenerating(name: String?) = publishToolStatus(name, "Preparing")

    override fun publishToolProgress(
        name: String?,
        @Suppress("UNUSED_PARAMETER") preview: String?,
    ) = publishToolStatus(name, "Running")

    override fun publishToolComplete(name: String?) = publishToolStatus(name, "Completed")

    override fun publishToolRisk(
        @Suppress("UNUSED_PARAMETER") name: String?,
    ) {
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            cancelPendingPartialLocked()
            toolLine = "⚠ Tool output redacted"
            enqueueCurrentLocked()
        }
    }

    override fun publishFinal(
        text: String,
        afterDelivery: (() -> Unit)?,
    ) {
        if (text.isEmpty()) return
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            finalQueued = true
            intents.removeAll { it.isPartial }
            enqueueLocked(text, isFinal = true, afterDelivery = afterDelivery)
        }
    }

    override fun close() {
        synchronized(stateLock) {
            epochOpen = false
            cancelPendingPartialLocked()
            intents.clear()
            writerWakeups.close()
        }
        writerScope.cancel()
    }

    private fun publishToolStatus(
        name: String?,
        status: String,
    ) {
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            cancelPendingPartialLocked()
            toolLine = formatTool(name, null, status)
            enqueueCurrentLocked()
        }
    }

    private fun cancelPendingPartialLocked() {
        pendingPartial?.cancel()
        pendingPartial = null
    }

    private fun schedulePartialLocked() {
        if (pendingPartial?.isActive == true) return
        val scheduledGeneration = generation
        pendingPartial =
            writerScope.launch {
                delay(PARTIAL_UPDATE_MILLIS)
                synchronized(stateLock) {
                    if (
                        scheduledGeneration != generation ||
                        !epochOpen ||
                        finalQueued
                    ) {
                        return@synchronized
                    }
                    pendingPartial = null
                    enqueuePartialLocked()
                }
            }
    }

    private fun enqueueCurrentLocked() {
        projectedTextLocked()?.let { enqueueLocked(it) }
    }

    private fun enqueuePartialLocked() {
        val text = projectedTextLocked() ?: return
        intents.removeAll { it.isPartial }
        intents.addLast(RenderIntent(generation, text, isPartial = true))
        writerWakeups.trySend(Unit)
    }

    private fun enqueueLocked(
        text: String,
        isFinal: Boolean = false,
        afterDelivery: (() -> Unit)? = null,
    ) {
        if (isFinal || intents.count { !it.isPartial } < MAX_PENDING_CONTROL_INTENTS) {
            intents.addLast(RenderIntent(generation, text, isFinal = isFinal, afterDelivery = afterDelivery))
            writerWakeups.trySend(Unit)
        }
    }

    private fun projectedTextLocked(): String? =
        when {
            assistantText.isNotEmpty() && toolLine != null -> "$assistantText\n\n$toolLine"
            assistantText.isNotEmpty() -> assistantText.toString()
            else -> toolLine
        }

    private fun formatTool(
        name: String?,
        data: Map<String, Any?>?,
        status: String,
    ): String {
        val config = ToolSchemaRegistry.getDisplayConfig(name)
        val detail = safeDetail(name, data)
        return buildString {
            append(config.iconEmoji)
            append(' ')
            append(config.name)
            if (detail != null) {
                append(": ")
                append(detail)
            }
            append(" — ")
            append(status)
        }
    }

    private fun safeDetail(
        name: String?,
        data: Map<String, Any?>?,
    ): String? {
        val key = SAFE_DETAIL_KEYS[name] ?: return null
        val value = data?.get(key) as? String ?: return null
        if (value.length > MAX_DETAIL_LENGTH || value.contains(SECRET_PATTERN)) return "[redacted]"
        return value
    }

    private companion object {
        const val PARTIAL_UPDATE_MILLIS = 200L
        const val MAX_PENDING_CONTROL_INTENTS = 128
        const val MAX_DETAIL_LENGTH = 240
        val SECRET_PATTERN = Regex("(?i)(api[_-]?key|token|secret|password|authorization|bearer)")
        val SAFE_DETAIL_KEYS =
            mapOf(
                "read_file" to "path",
                "write_file" to "path",
                "patch" to "path",
                "web_search" to "query",
                "browser_navigate" to "url",
            )
    }
}
