package com.m57.hermescontrol.glasses.service

import com.m57.hermescontrol.glasses.myvu.GlassesFontMode
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
 * Conservative ED70 teleprompter geometry used to decide when buffered prose
 * is long enough to open the response document and begin MYVU scrolling.
 *
 * The device exposes no page-overflow signal. These named estimates deliberately
 * leave a one-wrapped-line margin after the visible page and are the single place
 * to calibrate after device observations.
 */
internal object MyvuResponsePageLayout {
    const val OVERFLOW_MARGIN_LINES = 1

    // ED70 teleprompter estimates: 640 px-wide display at the two vendor font modes.
    private const val ED70_STANDARD_COLUMNS_PER_LINE = 30
    private const val ED70_STANDARD_VISIBLE_LINES = 11
    private const val ED70_LARGE_COLUMNS_PER_LINE = 22
    private const val ED70_LARGE_VISIBLE_LINES = 8

    fun pagePlusOneLineCapacity(fontMode: GlassesFontMode): Int =
        geometryFor(fontMode).visibleLines + OVERFLOW_MARGIN_LINES

    fun columnsPerLine(fontMode: GlassesFontMode): Int = geometryFor(fontMode).columnsPerLine

    fun isBeyondOverflowMargin(
        text: CharSequence,
        fontMode: GlassesFontMode,
    ): Boolean = estimatedWrappedLines(text, fontMode) >= pagePlusOneLineCapacity(fontMode)

    fun estimatedWrappedLines(
        text: CharSequence,
        fontMode: GlassesFontMode,
    ): Int {
        val geometry = geometryFor(fontMode)
        var lines = 0
        var lineStart = 0
        for (index in 0..text.length) {
            if (index == text.length || text[index] == '\n') {
                lines += wrappedLines(text, lineStart, index, geometry)
                lineStart = index + 1
            }
        }
        return lines
    }

    private fun geometryFor(fontMode: GlassesFontMode): PageGeometry =
        when (fontMode) {
            GlassesFontMode.Standard ->
                PageGeometry(
                    columnsPerLine = ED70_STANDARD_COLUMNS_PER_LINE,
                    visibleLines = ED70_STANDARD_VISIBLE_LINES,
                )
            GlassesFontMode.Large ->
                PageGeometry(
                    columnsPerLine = ED70_LARGE_COLUMNS_PER_LINE,
                    visibleLines = ED70_LARGE_VISIBLE_LINES,
                )
        }

    private fun wrappedLines(
        text: CharSequence,
        startIndex: Int,
        endIndex: Int,
        geometry: PageGeometry,
    ): Int {
        if (startIndex == endIndex) return 1

        var lines = 1
        var column = 0
        var index = startIndex
        while (index < endIndex) {
            if (text[index].isWhitespace()) {
                if (column == geometry.columnsPerLine) {
                    lines += 1
                    column = 0
                }
                column += 1
                index += 1
                continue
            }

            val wordStart = index
            while (index < endIndex && !text[index].isWhitespace()) index += 1
            val wordLength = index - wordStart
            if (column > 0 && column + wordLength > geometry.columnsPerLine) {
                lines += 1
                column = 0
            }
            val occupiedLines = (wordLength - 1) / geometry.columnsPerLine
            lines += occupiedLines
            column = ((wordLength - 1) % geometry.columnsPerLine) + 1
        }
        return lines
    }

    private data class PageGeometry(
        val columnsPerLine: Int,
        val visibleLines: Int,
    )
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
    private enum class DocumentPhase {
        Thinking,
        Response,
    }

    private data class RenderIntent(
        val generation: Long,
        val text: String,
        val documentPhase: DocumentPhase,
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
    private var responseRequested = false
    private val assistantText = StringBuilder()
    private var toolLine: String? = null
    private var pendingPartial: Job? = null

    init {
        writerScope.launch {
            var openedGeneration: Long? = null
            var openedDocumentPhase: DocumentPhase? = null
            for (ignored in writerWakeups) {
                while (true) {
                    val intent =
                        synchronized(stateLock) {
                            if (intents.isEmpty()) null else intents.removeFirst()
                        } ?: break
                    if (!isCurrentIntent(intent)) continue

                    val opensDocument =
                        openedGeneration != intent.generation || openedDocumentPhase != intent.documentPhase
                    val commands =
                        if (opensDocument) {
                            when (intent.documentPhase) {
                                DocumentPhase.Thinking -> renderer.openThinking(intent.text, readability())
                                DocumentPhase.Response -> renderer.openResponse(intent.text, readability())
                            }
                        } else {
                            when (intent.documentPhase) {
                                DocumentPhase.Thinking -> renderer.updateThinking(intent.text)
                                DocumentPhase.Response -> renderer.updateResponse(intent.text)
                            }
                        }
                    commands.forEach(writer::send)
                    val claimedFinalCallback =
                        synchronized(stateLock) {
                            val isCurrent = intent.generation == generation && epochOpen
                            if (opensDocument && isCurrent) {
                                openedGeneration = intent.generation
                                openedDocumentPhase = intent.documentPhase
                            }
                            if (intent.isFinal && isCurrent) intent.afterDelivery else null
                        }
                    claimedFinalCallback?.invoke()
                }
            }
        }
    }

    override fun startEpoch() {
        synchronized(stateLock) {
            generation += 1
            epochOpen = true
            finalQueued = false
            responseRequested = false
            assistantText.clear()
            toolLine = null
            cancelPendingPartialLocked()
            intents.clear()
            enqueueLocked(text = THINKING_TEXT, documentPhase = DocumentPhase.Thinking)
        }
    }

    override fun publishToken(token: String) {
        if (token.isEmpty()) return
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            assistantText.append(token)
            toolLine = null
            if (!responseRequested) {
                if (MyvuResponsePageLayout.isBeyondOverflowMargin(assistantText, readability().fontMode)) {
                    requestResponseLocked(assistantText.toString())
                }
            } else {
                schedulePartialLocked()
            }
        }
    }

    override fun publishToolStart(
        name: String?,
        data: Map<String, Any?>?,
    ) = publishToolStatus(name, data, "Starting")

    override fun publishToolGenerating(name: String?) = publishToolStatus(name, null, "Preparing")

    override fun publishToolProgress(
        name: String?,
        @Suppress("UNUSED_PARAMETER") preview: String?,
    ) = publishToolStatus(name, null, "Running")

    override fun publishToolComplete(name: String?) = publishToolStatus(name, null, "Completed")

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
            cancelPendingPartialLocked()
            intents.removeAll { it.documentPhase == DocumentPhase.Response }
            responseRequested = true
            enqueueLocked(
                text = text,
                documentPhase = DocumentPhase.Response,
                isFinal = true,
                afterDelivery = afterDelivery,
            )
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

    private fun isCurrentIntent(intent: RenderIntent): Boolean =
        synchronized(stateLock) {
            intent.generation == generation && epochOpen
        }

    private fun publishToolStatus(
        name: String?,
        data: Map<String, Any?>?,
        status: String,
    ) {
        synchronized(stateLock) {
            if (!epochOpen || finalQueued) return
            cancelPendingPartialLocked()
            toolLine = formatTool(name, data, status)
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
                        finalQueued ||
                        !responseRequested
                    ) {
                        return@synchronized
                    }
                    pendingPartial = null
                    enqueueLocked(
                        text = responseTextLocked(),
                        documentPhase = DocumentPhase.Response,
                        isPartial = true,
                    )
                }
            }
    }

    private fun requestResponseLocked(text: String) {
        check(!responseRequested)
        responseRequested = true
        cancelPendingPartialLocked()
        enqueueLocked(text = text, documentPhase = DocumentPhase.Response)
    }

    private fun enqueueCurrentLocked() {
        if (responseRequested) {
            enqueueLocked(text = responseTextLocked(), documentPhase = DocumentPhase.Response)
        } else {
            enqueueLocked(text = thinkingTextLocked(), documentPhase = DocumentPhase.Thinking)
        }
    }

    private fun enqueueLocked(
        text: String,
        documentPhase: DocumentPhase,
        isPartial: Boolean = false,
        isFinal: Boolean = false,
        afterDelivery: (() -> Unit)? = null,
    ) {
        if (
            isPartial ||
            isFinal ||
            documentPhase == DocumentPhase.Response ||
            intents.count { !it.isPartial } < MAX_PENDING_THINKING_INTENTS
        ) {
            if (isPartial) intents.removeAll { it.isPartial }
            intents.addLast(
                RenderIntent(
                    generation = generation,
                    text = text,
                    documentPhase = documentPhase,
                    isPartial = isPartial,
                    isFinal = isFinal,
                    afterDelivery = afterDelivery,
                ),
            )
            writerWakeups.trySend(Unit)
        }
    }

    private fun thinkingTextLocked(): String = toolLine?.let { "$THINKING_TEXT\n\n$it" } ?: THINKING_TEXT

    private fun responseTextLocked(): String =
        when {
            toolLine != null -> "$assistantText\n\n$toolLine"
            else -> assistantText.toString()
        }

    private fun formatTool(
        name: String?,
        data: Map<String, Any?>?,
        status: String,
    ): String {
        val config = ToolSchemaRegistry.getDisplayConfig(name)
        val detail = safeDetail(name, data)
        return buildString {
            append(config.summaryPrefix.ifBlank { "• " })
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
        const val THINKING_TEXT = "Thinking"
        const val PARTIAL_UPDATE_MILLIS = 200L
        const val MAX_PENDING_THINKING_INTENTS = 128
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
