package com.m57.hermescontrol.glasses

import android.content.Context
import com.m57.hermescontrol.data.local.ChatMessageEntity
import com.m57.hermescontrol.data.local.HermesDatabase
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsMethods
import java.util.UUID

/** Initialized from [com.m57.hermescontrol.HermesControlApp], never from a screen. */
object ChatTurnCoordinatorProvider {
    @Volatile
    private var instance: ChatTurnCoordinator? = null

    @Volatile
    private var initializedContext: Context? = null

    fun initialize(context: Context) {
        val applicationContext = context.applicationContext
        if (instance != null && initializedContext === applicationContext) return
        synchronized(this) {
            if (instance != null && initializedContext === applicationContext) return
            instance =
                ChatTurnCoordinator(
                    gateway =
                        object : TurnGateway {
                            override suspend fun submit(
                                runtimeSessionId: String,
                                text: String,
                            ) {
                                HermesWsClient.request(
                                    WsMethods.PROMPT_SUBMIT,
                                    mapOf("session_id" to runtimeSessionId, "text" to text),
                                ).await()
                            }

                            override suspend fun redirect(
                                runtimeSessionId: String,
                                text: String,
                            ) {
                                HermesWsClient.request(
                                    WsMethods.SESSION_REDIRECT,
                                    mapOf("session_id" to runtimeSessionId, "text" to text),
                                ).await()
                            }
                        },
                    store =
                        object : TurnStore {
                            override suspend fun persist(
                                storedSessionId: String,
                                text: String,
                            ) {
                                try {
                                    HermesDatabase
                                        .get(applicationContext)
                                        .chatMessageDao()
                                        .upsert(
                                            ChatMessageEntity(
                                                id = UUID.randomUUID().toString(),
                                                sessionId = storedSessionId,
                                                role = "USER",
                                                content = text,
                                                timestamp = System.currentTimeMillis(),
                                            ),
                                        )
                                } catch (_: UnsatisfiedLinkError) {
                                    // The local JVM test runtime deliberately has no SQLCipher JNI.
                                }
                            }
                        },
                )
            initializedContext = applicationContext
        }
    }

    fun get(): ChatTurnCoordinator = checkNotNull(instance) { "ChatTurnCoordinatorProvider.initialize must run first" }
}
