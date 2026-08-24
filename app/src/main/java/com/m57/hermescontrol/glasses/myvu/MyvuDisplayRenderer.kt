package com.m57.hermescontrol.glasses.myvu

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

enum class DisplayKind {
    Context,
    Input,
    Response,
    Status,
}

enum class GlassesFontMode(val vendorValue: Int) {
    Standard(0),
    Large(1),
}

data class GlassesReadability(
    val fontMode: GlassesFontMode = GlassesFontMode.Standard,
    val pacingMillis: Int = DEFAULT_PACING_MILLIS,
) {
    init {
        require(pacingMillis > 0) { "Pacing must be positive" }
    }

    companion object {
        const val DEFAULT_PACING_MILLIS = 300
    }
}

data class MyvuDisplayCommand(
    val receiverPackage: String,
    val senderPackage: String,
    val payload: String,
    val documentKey: String,
    val fontMode: GlassesFontMode? = null,
)

val List<MyvuDisplayCommand>.fontCommand: MyvuDisplayCommand?
    get() = firstOrNull { it.fontMode != null }

/**
 * Builds the proven MYVU teleprompter message pairs. Transport delivery stays
 * separate so display policy can be unit tested without a Binder dependency.
 */
class MyvuDisplayRenderer(
    private val documentId: () -> String = { UUID.randomUUID().toString() },
) {
    private data class DocumentIdentity(
        val fileKey: String,
        val msgId: String,
        val pacingMillis: Int,
    )

    private var activeDocument: DocumentIdentity? = null
    private var thinkingDocument: DocumentIdentity? = null
    private var responseDocument: DocumentIdentity? = null

    @Synchronized
    fun commandsFor(
        text: String,
        kind: DisplayKind,
        readability: GlassesReadability = GlassesReadability(),
    ): List<MyvuDisplayCommand> {
        require(text.isNotEmpty()) { "Display text cannot be empty" }
        if (kind == DisplayKind.Response) return openResponse(text, readability)

        val document = activeDocument ?: newDocument(readability).also { activeDocument = it }
        return openDocument(text, document, readability)
    }

    /**
     * Starts the visible turn marker on a fresh MYVU document.
     *
     * Thinking and Response are separate documents so that the response has one
     * uninterrupted scroll lifetime.
     */
    @Synchronized
    fun openThinking(
        text: String,
        readability: GlassesReadability,
    ): List<MyvuDisplayCommand> {
        val document = newDocument(readability)
        thinkingDocument = document
        activeDocument = document
        return openDocument(text, document, readability)
    }

    @Synchronized
    fun updateThinking(text: String): List<MyvuDisplayCommand> =
        updateDocument(
            text = text,
            document = checkNotNull(thinkingDocument) { "A thinking document must be opened first" },
        )

    @Synchronized
    fun openResponse(
        text: String,
        readability: GlassesReadability = GlassesReadability(),
    ): List<MyvuDisplayCommand> {
        val document = newDocument(readability)
        responseDocument = document
        activeDocument = document
        return openDocument(text, document, readability)
    }

    /**
     * Replays MYVU's required open-content pair for the same response identity.
     */
    @Synchronized
    fun updateResponse(text: String): List<MyvuDisplayCommand> =
        updateDocument(
            text = text,
            document = checkNotNull(responseDocument) { "A response document must be opened first" },
        )

    private fun newDocument(readability: GlassesReadability): DocumentIdentity =
        DocumentIdentity(
            fileKey = "${documentId()}/hermes-agent",
            msgId = UUID.randomUUID().toString(),
            pacingMillis = readability.pacingMillis,
        )

    private fun openDocument(
        text: String,
        document: DocumentIdentity,
        readability: GlassesReadability,
    ): List<MyvuDisplayCommand> =
        updateDocument(text, document) +
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = fontMode(readability),
                documentKey = document.fileKey,
                fontMode = readability.fontMode,
            )

    private fun updateDocument(
        text: String,
        document: DocumentIdentity,
    ): List<MyvuDisplayCommand> {
        require(text.isNotEmpty()) { "Display text cannot be empty" }
        return listOf(
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = openApp(text, document),
                documentKey = document.fileKey,
            ),
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = sendContent(text, document),
                documentKey = document.fileKey,
            ),
        )
    }

    private fun fontMode(readability: GlassesReadability): String =
        buildJsonObject {
            put("action", "system")
            put(
                "data",
                buildJsonObject {
                    put("action", "set_font_mode")
                    put("value", readability.fontMode.vendorValue)
                },
            )
        }.toString()

    private fun openApp(
        text: String,
        document: DocumentIdentity,
    ): String {
        val ext =
            buildJsonObject {
                put("blockNotification", true)
                put("currentPage", 0)
                put("fileKey", document.fileKey)
                put("msgId", document.msgId)
                put("nextTotalParagraphSize", 0)
                put("paragraphIndex", 0)
                put("prevTotalParagraphSize", 0)
                put("screenLocation", 2)
                put("sourceByteSize", text.encodeToByteArray().size)
                put("sourceTextOffset", 0)
                put("ticiMode", 1)
                put("ticiSpeed", document.pacingMillis)
                put("totalPage", 1)
                put("totalPart", 1)
                put("totalTextLength", text.length)
                put("version", 2)
            }.toString()
        return buildJsonObject {
            put("action", "app")
            put(
                "data",
                buildJsonObject {
                    put("launchMode", "scene")
                    put("action", "open_app")
                    put("pkg", TICI_PACKAGE)
                    put("app_name", TICI_PACKAGE)
                    put("ext", ext)
                },
            )
        }.toString()
    }

    private fun sendContent(
        text: String,
        document: DocumentIdentity,
    ): String {
        val content =
            buildJsonObject {
                put("currentPage", 0)
                put("fileKey", document.fileKey)
                put("msgId", document.msgId)
                put("part", 0)
                put("sourceText", text)
            }.toString()
        return buildJsonObject {
            put("action", "tici")
            put(
                "data",
                buildJsonObject {
                    put("action", "send_content")
                    put("value", content)
                },
            )
        }.toString()
    }

    private companion object {
        const val PERSONAL_PACKAGE = "com.m57.hermescontrol"
        const val TICI_PACKAGE = "com.upuphone.ar.tici"
    }
}
