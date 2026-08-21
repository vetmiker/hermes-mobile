package com.m57.hermescontrol.glasses.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.data.ws.WsEvent
import com.m57.hermescontrol.glasses.ChatTurnCoordinatorProvider
import com.m57.hermescontrol.glasses.GlassesModeControllerProvider
import com.m57.hermescontrol.glasses.GlassesModeState
import com.m57.hermescontrol.glasses.TurnLease
import com.m57.hermescontrol.glasses.myvu.DisplayKind
import com.m57.hermescontrol.glasses.myvu.GlassesReadabilityStore
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import com.m57.hermescontrol.glasses.myvu.MyvuTransport
import com.m57.hermescontrol.glasses.myvu.MyvuTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

internal data class MyvuGlassesStartRequest(
    val token: String?,
    val storedSessionId: String?,
    val runtimeSessionId: String?,
    val initialDisplay: String?,
) {
    val isValid: Boolean
        get() =
            !token.isNullOrBlank() &&
                !storedSessionId.isNullOrBlank() &&
                !runtimeSessionId.isNullOrBlank() &&
                !initialDisplay.isNullOrBlank()
}

/**
 * Owns the visible MYVU microphone session. It is only started by a foreground
 * activity after runtime permission succeeds; host traffic never starts capture.
 */
class MyvuGlassesService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var transport: MyvuTransport? = null
    private var audioServer: MyvuAudioServer? = null
    private var pcmCapture: MyvuPcmCapture? = null
    private var controlServer: MyvuControlServer? = null
    private var activeVoiceLease: TurnLease? = null
    private val renderer = MyvuDisplayRenderer()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        when (intent?.action) {
            ACTION_START -> {
                val request =
                    MyvuGlassesStartRequest(
                        token = intent.getStringExtra(EXTRA_AUDIO_TOKEN),
                        storedSessionId = intent.getStringExtra(EXTRA_STORED_SESSION_ID),
                        runtimeSessionId = intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID),
                        initialDisplay = intent.getStringExtra(EXTRA_INITIAL_DISPLAY),
                    )
                if (!request.isValid ||
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.w(TAG, "MYVU_SERVICE start refused microphone permission or payload")
                    stopSelf(startId)
                } else {
                    promoteToForeground()
                    start(request)
                }
                START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSession()
                stopSelf(startId)
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSession()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun start(request: MyvuGlassesStartRequest) {
        if (audioServer != null) {
            // A different visible chat supersedes the old mode atomically:
            // cleanup increments its generation before this new start acquires one.
            stopSession()
        }
        val token = checkNotNull(request.token)
        val storedSessionId = checkNotNull(request.storedSessionId)
        val runtimeSessionId = checkNotNull(request.runtimeSessionId)
        val initialDisplay = checkNotNull(request.initialDisplay)
        val starting = GlassesModeControllerProvider.controller.start(storedSessionId, runtimeSessionId)
        val control =
            MyvuControlServer(
                token = token,
                health = {
                    GlassesModeControllerProvider.controller.snapshot.value.let {
                        JSONObject()
                            .put("generation", it.generation)
                            .put("state", it.state.name)
                            .put("activeStreamId", it.activeStreamId ?: JSONObject.NULL)
                            .put("stockBound", transport != null)
                            .put("audioReady", pcmCapture != null)
                            .put("protocolVersion", 1)
                    }
                },
                display = { body -> render(body, DisplayKind.Status) },
                transcript = { generation, streamId, utteranceId, text ->
                    val acceptance =
                        GlassesModeControllerProvider.controller.acceptTranscript(
                            generation,
                            streamId,
                            utteranceId,
                            text,
                        )
                    val fence = acceptance.fence
                    if (acceptance.accepted && fence != null) {
                        pcmCapture?.close()
                        pcmCapture = null
                        serviceScope.launch {
                            val controller = GlassesModeControllerProvider.controller
                            if (!controller.isTranscriptFenceActive(fence)) return@launch
                            val coordinator = ChatTurnCoordinatorProvider.get()
                            val reservation =
                                coordinator.reserveVoice(
                                    fence.storedSessionId,
                                    fence.runtimeSessionId,
                                    fence.utteranceId,
                                )
                            if (!controller.isTranscriptFenceActive(fence)) {
                                coordinator.discardVoice(reservation)
                                return@launch
                            }
                            val outcome = coordinator.commitVoice(reservation, text)
                            if (outcome.accepted) {
                                activeVoiceLease = outcome.lease
                            } else if (controller.isTranscriptFenceActive(fence)) {
                                controller.suspend("Another chat turn is still completing")
                            }
                        }
                    }
                    acceptance
                },
                transcriptEnded = {
                    serviceScope.launch {
                        stopSession()
                        stopSelf()
                    }
                },
                control = { action ->
                    if (action == "end") {
                        stopSession()
                        stopSelf()
                        true
                    } else {
                        false
                    }
                },
            )
        try {
            control.start()
            controlServer = control
        } catch (error: Exception) {
            Log.e(TAG, "MYVU_SERVICE control server start failed", error)
            stopSession()
            stopSelf()
            return
        }
        val server =
            MyvuAudioServer(
                token = token,
                onClientReady = {
                    serviceScope.launch {
                        val snapshot = GlassesModeControllerProvider.controller.snapshot.value
                        if (
                            snapshot.state == GlassesModeState.SUSPENDED &&
                            GlassesModeControllerProvider.controller.recover(
                                snapshot.generation,
                                snapshot.storedSessionId ?: return@launch,
                                snapshot.runtimeSessionId ?: return@launch,
                            )
                        ) {
                            resumeCapture()
                        }
                    }
                },
                onTransportFailure = { failure ->
                    serviceScope.launch {
                        pcmCapture?.close()
                        pcmCapture = null
                        GlassesModeControllerProvider
                            .controller
                            .suspend(
                                when (failure) {
                                    AudioTransportFailure.OVERFLOW -> "STT sidecar could not keep up with audio"
                                    AudioTransportFailure.DISCONNECTED -> "STT sidecar audio connection lost"
                                },
                            )
                    }
                },
            )
        try {
            server.start()
            audioServer = server
            Log.i(TAG, "MYVU_SERVICE server listening port=${server.boundPort}")
        } catch (error: Exception) {
            Log.e(TAG, "MYVU_SERVICE server start failed", error)
            stopSession()
            stopSelf()
            return
        }
        val currentTransport = MyvuTransport(applicationContext)
        transport = currentTransport
        if (!currentTransport.bind()) {
            Log.e(TAG, "MYVU_SERVICE stock bind request rejected")
            stopSession()
            stopSelf()
            return
        }
        serviceScope.launch {
            val ready =
                withTimeoutOrNull(READY_TIMEOUT_MILLIS) {
                    currentTransport.state.filterIsInstance<MyvuTransportState.Ready>().first()
                }
            if (ready == null || transport !== currentTransport) {
                Log.e(TAG, "MYVU_SERVICE stock transport not ready timeoutMillis=$READY_TIMEOUT_MILLIS")
                stopSession()
                stopSelf()
                return@launch
            }
            if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "MYVU_SERVICE SCO capture unavailable")
                stopSession()
                stopSelf()
                return@launch
            }
            Log.i(TAG, "MYVU_SERVICE stock transport ready display=available")
            render(initialDisplay, DisplayKind.Context)
            if (!GlassesModeControllerProvider.controller.initialDisplayCompleted(
                    starting.generation,
                    storedSessionId,
                    runtimeSessionId,
                )
            ) {
                Log.e(TAG, "MYVU_SERVICE initial display superseded before capture")
                stopSession()
                stopSelf()
                return@launch
            }
            val capture = createPcmCapture()
            if (capture.start().isFailure) {
                Log.e(TAG, "MYVU_SERVICE SCO capture failed")
                stopSession()
                stopSelf()
                return@launch
            }
            pcmCapture = capture
            Log.i(TAG, "MYVU_SERVICE capture started protocol=raw-pcm16 sampleRate=16000")
            serviceScope.launch {
                HermesWsClient.events.collect { event ->
                    if (event !is WsEvent.MessageComplete) return@collect
                    val current = GlassesModeControllerProvider.controller.snapshot.value
                    val runtimeSessionId = current.runtimeSessionId ?: return@collect
                    val storedSessionId = current.storedSessionId ?: return@collect
                    val terminalText = event.text
                    if (runtimeSessionId != event.sessionId) return@collect
                    val lease = activeVoiceLease
                    if (lease != null) {
                        ChatTurnCoordinatorProvider.get().completeTerminal(lease, runtimeSessionId, terminalText)
                    }
                    if (lease == null && current.state != GlassesModeState.PHONE_PRIORITY) return@collect
                    activeVoiceLease = null
                    if (GlassesModeControllerProvider.controller.acceptTerminal(
                            current.generation,
                            storedSessionId,
                            runtimeSessionId,
                            terminalText,
                        )
                    ) {
                        render(terminalText, DisplayKind.Response)
                        if (GlassesModeControllerProvider.controller.displayCompleted(
                                current.generation,
                                storedSessionId,
                                runtimeSessionId,
                            )
                        ) {
                            resumeCapture()
                        }
                    }
                }
            }
            serviceScope.launch {
                GlassesModeControllerProvider.controller.snapshot.collect { snapshot ->
                    if (snapshot.state == GlassesModeState.PHONE_PRIORITY) {
                        pcmCapture?.close()
                        pcmCapture = null
                    }
                }
            }
            serviceScope.launch {
                currentTransport.state.collect { state ->
                    if (
                        transport === currentTransport &&
                        (state is MyvuTransportState.Disconnected || state is MyvuTransportState.Failed)
                    ) {
                        Log.e(TAG, "MYVU_SERVICE transport stopped state=$state")
                        stopSession()
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun stopSession() {
        val hadSession = pcmCapture != null || audioServer != null || transport != null
        pcmCapture?.close()
        pcmCapture = null
        controlServer?.close()
        controlServer = null
        activeVoiceLease = null
        transport?.unbind()
        transport = null
        audioServer?.close()
        audioServer = null
        if (hadSession) Log.i(TAG, "MYVU_SERVICE stopped")
        GlassesModeControllerProvider.controller.end()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createPcmCapture(): MyvuPcmCapture =
        MyvuPcmCapture(getSystemService(AudioManager::class.java)) { pcm, size ->
            audioServer?.publish(pcm, size)
        }

    private fun resumeCapture() {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            GlassesModeControllerProvider.controller.suspend("SCO capture permission unavailable")
            return
        }
        if (
            pcmCapture != null ||
            GlassesModeControllerProvider.controller.snapshot.value.state != GlassesModeState.LISTENING
        ) {
            return
        }
        val capture = createPcmCapture()
        if (capture.start().isSuccess) {
            pcmCapture = capture
        } else {
            GlassesModeControllerProvider.controller.suspend(
                "SCO capture recovery failed",
            )
        }
    }

    private fun render(
        text: String,
        kind: DisplayKind,
    ) {
        val currentTransport = transport ?: return
        renderer.commandsFor(
            text,
            kind,
            GlassesReadabilityStore.readability.value,
        ).forEach { currentTransport.send(it) }
    }

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.myvu_audio_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.myvu_audio_notification_title))
                .setContentText(getString(R.string.myvu_audio_notification_text))
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "com.m57.hermescontrol.glasses.action.START"
        const val ACTION_STOP = "com.m57.hermescontrol.glasses.action.STOP"
        const val EXTRA_AUDIO_TOKEN = "com.m57.hermescontrol.glasses.extra.AUDIO_TOKEN"
        const val EXTRA_STORED_SESSION_ID = "com.m57.hermescontrol.glasses.extra.STORED_SESSION_ID"
        const val EXTRA_RUNTIME_SESSION_ID = "com.m57.hermescontrol.glasses.extra.RUNTIME_SESSION_ID"
        const val EXTRA_INITIAL_DISPLAY = "com.m57.hermescontrol.glasses.extra.INITIAL_DISPLAY"
        const val DEFAULT_AUDIO_TOKEN = BuildConfig.MYVU_BRIDGE_TOKEN
        private const val CHANNEL_ID = "myvu_audio"
        private const val NOTIFICATION_ID = 8932
        private const val READY_TIMEOUT_MILLIS = 10_000L
        private const val TAG = "HermesMyvuAudio"
    }
}
