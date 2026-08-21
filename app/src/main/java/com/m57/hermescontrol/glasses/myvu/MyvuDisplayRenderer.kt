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
    private var activeDocumentKey: String? = null

    @Synchronized
    fun commandsFor(
        text: String,
        kind: DisplayKind,
        readability: GlassesReadability = GlassesReadability(),
    ): List<MyvuDisplayCommand> {
        require(text.isNotEmpty()) { "Display text cannot be empty" }
        val documentKey =
            if (kind == DisplayKind.Response || activeDocumentKey == null) {
                "${documentId()}/hermes-agent".also { activeDocumentKey = it }
            } else {
                checkNotNull(activeDocumentKey)
            }
        val openPayload = openApp(text, documentKey, readability.pacingMillis)
        val contentPayload = sendContent(text, documentKey)
        val fontPayload =
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
        return listOf(
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = openPayload,
                documentKey = documentKey,
            ),
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = contentPayload,
                documentKey = documentKey,
            ),
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = fontPayload,
                documentKey = documentKey,
                fontMode = readability.fontMode,
            ),
        )
    }

    /**
     * Replaces the visible text in the active response document without opening
     * another MYVU scene.
     */
    @Synchronized
    fun updateResponse(text: String): List<MyvuDisplayCommand> {
        require(text.isNotEmpty()) { "Display text cannot be empty" }
        val documentKey = checkNotNull(activeDocumentKey) { "A response document must be opened first" }
        return listOf(
            MyvuDisplayCommand(
                receiverPackage = MyvuProtocol.LAUNCHER_RECEIVER,
                senderPackage = PERSONAL_PACKAGE,
                payload = sendContent(text, documentKey),
                documentKey = documentKey,
            ),
        )
    }

    private fun openApp(
        text: String,
        documentKey: String,
        pacingMillis: Int,
    ): String {
        val messageId = UUID.randomUUID().toString()
        val ext =
            buildJsonObject {
                put("blockNotification", true)
                put("currentPage", 0)
                put("fileKey", documentKey)
                put("msgId", messageId)
                put("nextTotalParagraphSize", 0)
                put("paragraphIndex", 0)
                put("prevTotalParagraphSize", 0)
                put("screenLocation", 2)
                put("sourceByteSize", text.encodeToByteArray().size)
                put("sourceTextOffset", 0)
                put("ticiMode", 1)
                put("ticiSpeed", pacingMillis)
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
        documentKey: String,
    ): String {
        val content =
            buildJsonObject {
                put("currentPage", 0)
                put("fileKey", documentKey)
                put("msgId", UUID.randomUUID().toString())
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
