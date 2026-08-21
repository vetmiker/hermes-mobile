package com.m57.hermescontrol.glasses.service

import android.Manifest
import android.annotation.SuppressLint
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
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.ws.HermesWsClient
import com.m57.hermescontrol.glasses.ChatTurnCoordinatorProvider
import com.m57.hermescontrol.glasses.GlassesModeControllerProvider
import com.m57.hermescontrol.glasses.GlassesModeSnapshot
import com.m57.hermescontrol.glasses.GlassesModeState
import com.m57.hermescontrol.glasses.TranscriptFence
import com.m57.hermescontrol.glasses.TurnLease
import com.m57.hermescontrol.glasses.VoiceTranscriptUiEvent
import com.m57.hermescontrol.glasses.myvu.DisplayKind
import com.m57.hermescontrol.glasses.myvu.GlassesReadabilityStore
import com.m57.hermescontrol.glasses.myvu.MyvuDisplayRenderer
import com.m57.hermescontrol.glasses.myvu.MyvuTransport
import com.m57.hermescontrol.glasses.myvu.MyvuTransportState
import com.m57.hermescontrol.glasses.speech.LocalSpeechPipeline
import com.m57.hermescontrol.glasses.speech.WhisperEngine
import com.m57.hermescontrol.glasses.speech.WhisperModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class MyvuGlassesStartRequest(
    val storedSessionId: String?,
    val runtimeSessionId: String?,
    val initialDisplay: String?,
) {
    val isValid: Boolean
        get() =
            !storedSessionId.isNullOrBlank() &&
                !runtimeSessionId.isNullOrBlank() &&
                !initialDisplay.isNullOrBlank()
}

internal data class MyvuGlassesMirrorPayload(
    val generation: Long,
    val storedSessionId: String?,
    val runtimeSessionId: String?,
    val mirrorId: String?,
    val text: String?,
) {
    val isValid: Boolean
        get() =
            generation >= 0 &&
                !storedSessionId.isNullOrBlank() &&
                !runtimeSessionId.isNullOrBlank() &&
                !mirrorId.isNullOrBlank() &&
                !text.isNullOrBlank()
}

internal class MyvuPreparationSessionGate {
    internal data class Session(
        val job: Job,
        val generation: Long,
        val storedSessionId: String,
        val runtimeSessionId: String,
        val transport: Any,
    )

    private var active: Session? = null

    fun start(
        job: Job,
        generation: Long,
        storedSessionId: String,
        runtimeSessionId: String,
        transport: Any,
    ): Session = Session(job, generation, storedSessionId, runtimeSessionId, transport).also { active = it }

    fun invalidate() {
        active = null
    }

    fun isCurrent(session: Session): Boolean = active === session && session.job.isActive

    fun ifCurrent(
        session: Session,
        effect: () -> Unit,
    ) {
        if (isCurrent(session)) effect()
    }
}

internal suspend fun terminalLeaseReleased(
    snapshot: GlassesModeSnapshot,
    voiceLease: TurnLease?,
    text: String,
    completeVoice: suspend (TurnLease, String, String) -> Boolean,
    completePhone: suspend (String, String) -> TurnLease?,
): Boolean {
    val runtimeSessionId = snapshot.runtimeSessionId ?: return false
    return when {
        voiceLease != null -> {
            if (voiceLease.runtimeSessionId != runtimeSessionId) return false
            completeVoice(voiceLease, runtimeSessionId, text)
        }

        snapshot.state == GlassesModeState.PHONE_PRIORITY -> {
            completePhone(runtimeSessionId, text) != null
        }

        else -> false
    }
}

/** Owns the visible MYVU session. Raw PCM never leaves this process. */
class MyvuGlassesService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = MyvuDisplayRenderer()
    private var transport: MyvuTransport? = null
    private var pcmCapture: MyvuPcmCapture? = null
    private var engine: WhisperEngine? = null
    private var pipeline: LocalSpeechPipeline? = null
    private var activeVoiceLease: TurnLease? = null
    private var sessionEventRouter: MyvuSessionEventRouter? = null
    private var sessionJob: Job? = null
    private val preparationSessions = MyvuPreparationSessionGate()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        when (intent?.action) {
            ACTION_MIRROR_PHONE -> {
                renderPhoneMirror(intent.toMirrorPayload())
                START_NOT_STICKY
            }
            ACTION_MIRROR_RESPONSE -> {
                renderTerminalMirror(intent.toMirrorPayload())
                START_NOT_STICKY
            }
            ACTION_START -> {
                val request =
                    MyvuGlassesStartRequest(
                        storedSessionId = intent.getStringExtra(EXTRA_STORED_SESSION_ID),
                        runtimeSessionId = intent.getStringExtra(EXTRA_RUNTIME_SESSION_ID),
                        initialDisplay = intent.getStringExtra(EXTRA_INITIAL_DISPLAY),
                    )
                if (!request.isValid || !hasMicrophonePermission()) {
                    Log.w(TAG, "MYVU_SERVICE start refused microphone permission or payload")
                    stopSelf(startId)
                } else {
                    promoteToForeground(getString(R.string.myvu_audio_preparing_text))
                    start(
                        checkNotNull(request.storedSessionId),
                        checkNotNull(request.runtimeSessionId),
                        checkNotNull(request.initialDisplay),
                    )
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

    private fun start(
        storedSessionId: String,
        runtimeSessionId: String,
        initialDisplay: String,
    ) {
        stopSession()
        val job = SupervisorJob(serviceScope.coroutineContext[Job])
        sessionJob = job
        val scope = CoroutineScope(job + Dispatchers.Main.immediate)
        val starting = GlassesModeControllerProvider.controller.start(storedSessionId, runtimeSessionId)
        val currentTransport = MyvuTransport(applicationContext)
        transport = currentTransport
        val preparation =
            preparationSessions.start(
                job = job,
                generation = starting.generation,
                storedSessionId = storedSessionId,
                runtimeSessionId = runtimeSessionId,
                transport = currentTransport,
            )
        if (!currentTransport.bind()) {
            preparationFailed(preparation, "MYVU display binding was rejected")
            return
        }
        scope.launch {
            try {
                val ready =
                    withTimeoutOrNull(
                        READY_TIMEOUT_MILLIS,
                    ) { currentTransport.state.filterIsInstance<MyvuTransportState.Ready>().first() }
                if (ready == null) {
                    preparationFailed(preparation, "MYVU display did not become ready")
                    return@launch
                }
                if (!ownsPreparation(preparation)) return@launch
                render(initialDisplay, DisplayKind.Context)
                val models =
                    try {
                        WhisperModelStore(applicationContext).prepare { showPreparation(preparation, it) }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        preparationFailed(preparation, error.message ?: "Model preparation failed")
                        return@launch
                    }
                if (!ownsPreparation(preparation)) return@launch
                val localEngine = WhisperEngine()
                try {
                    localEngine.openAwait(models)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    if (ownsPreparation(preparation)) {
                        preparationFailed(preparation, error.message ?: "Native model load failed")
                    } else {
                        localEngine.close()
                    }
                    return@launch
                }
                if (!ownsPreparation(preparation)) {
                    localEngine.close()
                    return@launch
                }
                engine = localEngine
                if (!GlassesModeControllerProvider.controller.initialDisplayCompleted(
                        starting.generation,
                        storedSessionId,
                        runtimeSessionId,
                    )
                ) {
                    return@launch
                }
                if (!ownsPreparation(preparation)) return@launch
                promoteToForeground(getString(R.string.myvu_audio_listening_text))
                resumeCapture(scope)
                observeSession(scope, currentTransport)
            } catch (cancellation: CancellationException) {
                throw cancellation
            }
        }
    }

    private fun observeSession(
        scope: CoroutineScope,
        currentTransport: MyvuTransport,
    ) {
        val eventRouter =
            MyvuSessionEventRouter(
                publisher =
                    MyvuTurnStreamPublisher(
                        renderer = renderer,
                        readability = { GlassesReadabilityStore.readability.value },
                        writer = MyvuCommandWriter(currentTransport::send),
                    ),
                currentSnapshot = { GlassesModeControllerProvider.controller.snapshot.value },
                onFinalDelivered = { snapshot, text ->
                    serviceScope.launch { completeTerminalAfterDisplay(snapshot, text, scope) }
                },
            )
        sessionEventRouter = eventRouter

        scope.launch {
            HermesWsClient.events.collect(eventRouter::route)
        }
        scope.launch {
            GlassesModeControllerProvider.controller.snapshot.collect { snapshot ->
                when (snapshot.state) {
                    GlassesModeState.PHONE_PRIORITY -> stopCapture()
                    GlassesModeState.LISTENING -> resumeCapture(scope)
                    else -> Unit
                }
            }
        }
        scope.launch {
            currentTransport.state.collect { state ->
                if (
                    transport === currentTransport &&
                    (state is MyvuTransportState.Disconnected || state is MyvuTransportState.Failed)
                ) {
                    stopSession()
                    stopSelf()
                }
            }
        }
    }

    private suspend fun completeTerminalAfterDisplay(
        snapshot: GlassesModeSnapshot,
        text: String,
        scope: CoroutineScope,
    ) {
        val storedSessionId = snapshot.storedSessionId ?: return
        val runtimeSessionId = snapshot.runtimeSessionId ?: return
        val voiceLease = activeVoiceLease
        val coordinator = ChatTurnCoordinatorProvider.get()
        if (
            !terminalLeaseReleased(
                snapshot = snapshot,
                voiceLease = voiceLease,
                text = text,
                completeVoice = coordinator::completeTerminal,
                completePhone = coordinator::completeTerminalForRuntime,
            )
        ) {
            return
        }
        if (voiceLease != null && activeVoiceLease == voiceLease) activeVoiceLease = null
        if (
            GlassesModeControllerProvider.controller.acceptTerminal(
                snapshot.generation,
                storedSessionId,
                runtimeSessionId,
                text,
            ) &&
            GlassesModeControllerProvider.controller.displayCompleted(
                snapshot.generation,
                storedSessionId,
                runtimeSessionId,
            )
        ) {
            resumeCapture(scope)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resumeCapture(scope: CoroutineScope) {
        if (
            !hasMicrophonePermission() ||
            pcmCapture != null ||
            GlassesModeControllerProvider.controller.snapshot.value.state != GlassesModeState.LISTENING
        ) {
            return
        }
        val localEngine = engine ?: return
        localEngine.resetVad {
            if (it.isFailure) {
                scope.launch { pipelineFailed(checkNotNull(it.exceptionOrNull())) }
                return@resetVad
            }
            if (
                pcmCapture != null ||
                GlassesModeControllerProvider.controller.snapshot.value.state != GlassesModeState.LISTENING
            ) {
                return@resetVad
            }
            val localPipeline =
                LocalSpeechPipeline(
                    engine = localEngine,
                    onUtterance = { pcm -> scope.launch { endpointAndTranscribe(pcm) } },
                    onFailure = { error -> scope.launch { pipelineFailed(error) } },
                )
            val capture =
                MyvuPcmCapture(
                    audioManager = getSystemService(AudioManager::class.java),
                    onPcm = { pcm, size -> localPipeline.offer(pcm, size) },
                )
            if (capture.start().isSuccess) {
                pipeline = localPipeline
                pcmCapture = capture
            } else {
                localPipeline.close()
                pipelineFailed(IllegalStateException("SCO capture recovery failed"))
            }
        }
    }

    private fun endpointAndTranscribe(pcm: ByteArray) {
        val controller = GlassesModeControllerProvider.controller
        val snapshot = controller.snapshot.value
        val streamId = snapshot.activeStreamId ?: return
        val fence =
            controller.beginTranscription(
                snapshot.generation,
                streamId,
                "${snapshot.generation}:${System.nanoTime()}",
            ) ?: return
        pcmCapture?.close()
        pcmCapture = null
        pipeline?.stopInput()
        pipeline = null
        engine?.transcribe(pcm) { result ->
            serviceScope.launch {
                result.onSuccess { text -> completeTranscript(fence, text) }.onFailure { error ->
                    if (controller.failTranscript(
                            fence,
                            error.message ?: "Native transcription failed",
                        )
                    ) {
                        resumeCapture(serviceScope)
                    }
                }
            }
        }
    }

    private fun completeTranscript(
        fence: TranscriptFence,
        text: String,
    ) {
        val controller = GlassesModeControllerProvider.controller
        val acceptance = controller.completeTranscript(fence, text)
        if (acceptance.ended) {
            stopSession()
            stopSelf()
            return
        }
        if (!acceptance.accepted) {
            if (controller.snapshot.value.state == GlassesModeState.LISTENING) {
                resumeCapture(serviceScope)
            }
            return
        }
        render(text, DisplayKind.Input)
        ChatTurnCoordinatorProvider.publishVoiceTranscript(
            VoiceTranscriptUiEvent.Published(
                storedSessionId = fence.storedSessionId,
                runtimeSessionId = fence.runtimeSessionId,
                utteranceId = fence.utteranceId,
                text = text,
            ),
        )
        serviceScope.launch {
            try {
                val coordinator = ChatTurnCoordinatorProvider.get()
                if (!controller.isTranscriptFenceActive(fence)) {
                    submissionFailed(fence, "Voice submission cancelled")
                    return@launch
                }
                val reservation =
                    coordinator.reserveVoice(
                        fence.storedSessionId,
                        fence.runtimeSessionId,
                        fence.utteranceId,
                    )
                if (!controller.isTranscriptFenceActive(fence)) {
                    coordinator.discardVoice(reservation)
                    submissionFailed(fence, "Voice submission cancelled")
                    return@launch
                }
                val outcome = coordinator.commitVoice(reservation, text)
                if (outcome.accepted) {
                    activeVoiceLease = outcome.lease
                } else {
                    submissionFailed(fence, "Another chat turn is still completing")
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                submissionFailed(fence, error.message ?: "Voice submission failed")
            }
        }
    }

    private fun pipelineFailed(error: Throwable) {
        stopCapture()
        val detail = error.message ?: "Local speech pipeline failed"
        GlassesModeControllerProvider.controller.suspend(detail)
        render(detail, DisplayKind.Status)
        promoteToForeground(getString(R.string.myvu_audio_suspended_text))
    }

    private fun submissionFailed(
        fence: TranscriptFence,
        detail: String,
    ) {
        ChatTurnCoordinatorProvider.publishVoiceTranscript(
            VoiceTranscriptUiEvent.SubmissionFailed(
                storedSessionId = fence.storedSessionId,
                runtimeSessionId = fence.runtimeSessionId,
                utteranceId = fence.utteranceId,
            ),
        )
        activeVoiceLease = null
        if (GlassesModeControllerProvider.controller.failSubmission(fence, detail)) {
            render(detail, DisplayKind.Status)
            promoteToForeground(getString(R.string.myvu_audio_suspended_text))
        }
    }

    private fun preparationFailed(
        preparation: MyvuPreparationSessionGate.Session,
        detail: String,
    ) {
        if (!ownsPreparation(preparation)) return
        stopCapture()
        engine?.close()
        engine = null
        GlassesModeControllerProvider.controller.error(detail)
        render(detail, DisplayKind.Status)
        promoteToForeground(getString(R.string.myvu_audio_error_text))
    }

    private fun stopCapture() {
        pcmCapture?.close()
        pcmCapture = null
        pipeline?.close()
        pipeline = null
    }

    private fun stopSession() {
        preparationSessions.invalidate()
        sessionEventRouter?.close()
        sessionEventRouter = null
        sessionJob?.cancel()
        sessionJob = null
        stopCapture()
        engine?.close()
        engine = null
        activeVoiceLease = null
        transport?.unbind()
        transport = null
        GlassesModeControllerProvider.controller.end()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun Intent.toMirrorPayload() =
        MyvuGlassesMirrorPayload(
            generation = getLongExtra(EXTRA_GENERATION, -1),
            storedSessionId = getStringExtra(EXTRA_STORED_SESSION_ID),
            runtimeSessionId = getStringExtra(EXTRA_RUNTIME_SESSION_ID),
            mirrorId = getStringExtra(EXTRA_MIRROR_ID),
            text = getStringExtra(EXTRA_DISPLAY_TEXT),
        )

    private fun renderPhoneMirror(payload: MyvuGlassesMirrorPayload) {
        if (!payload.isValid) return
        if (GlassesModeControllerProvider.controller.acceptPhoneMirror(
                payload.generation,
                checkNotNull(payload.storedSessionId),
                checkNotNull(payload.runtimeSessionId),
                checkNotNull(payload.mirrorId),
            )
        ) {
            render(checkNotNull(payload.text), DisplayKind.Input)
        }
    }

    private fun renderTerminalMirror(payload: MyvuGlassesMirrorPayload) {
        if (!payload.isValid) return
        val controller = GlassesModeControllerProvider.controller
        if (controller.acceptTerminal(
                payload.generation,
                checkNotNull(payload.storedSessionId),
                checkNotNull(payload.runtimeSessionId),
                checkNotNull(payload.text),
            )
        ) {
            render(checkNotNull(payload.text), DisplayKind.Response)
            if (controller.displayCompleted(
                    payload.generation,
                    checkNotNull(payload.storedSessionId),
                    checkNotNull(payload.runtimeSessionId),
                )
            ) {
                resumeCapture(serviceScope)
            }
        }
    }

    private fun render(
        text: String,
        kind: DisplayKind,
    ) {
        val currentTransport = transport ?: return
        renderer.commandsFor(text, kind, GlassesReadabilityStore.readability.value).forEach(currentTransport::send)
    }

    private fun showPreparation(
        preparation: MyvuPreparationSessionGate.Session,
        text: String,
    ) {
        serviceScope.launch {
            if (!ownsPreparation(preparation)) return@launch
            render(text, DisplayKind.Status)
            promoteToForeground(text)
        }
    }

    private fun ownsPreparation(preparation: MyvuPreparationSessionGate.Session): Boolean {
        val snapshot = GlassesModeControllerProvider.controller.snapshot.value
        return preparationSessions.isCurrent(preparation) &&
            sessionJob === preparation.job &&
            transport === preparation.transport &&
            snapshot.generation == preparation.generation &&
            snapshot.storedSessionId == preparation.storedSessionId &&
            snapshot.runtimeSessionId == preparation.runtimeSessionId
    }

    private fun promoteToForeground(text: String) {
        getSystemService(
            NotificationManager::class.java,
        ).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.myvu_audio_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID,
            ).setSmallIcon(
                R.mipmap.ic_launcher,
            ).setContentTitle(
                getString(R.string.myvu_audio_notification_title),
            ).setContentText(text).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun hasMicrophonePermission() =
        checkSelfPermission(
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private suspend fun WhisperEngine.openAwait(models: WhisperModelStore.ReadyModels): Unit =
        suspendCancellableCoroutine { continuation ->
            open(
                models,
            ) { result -> result.onSuccess { continuation.resume(Unit) }.onFailure(continuation::resumeWithException) }
            continuation.invokeOnCancellation { close() }
        }

    companion object {
        const val ACTION_START = "com.m57.hermescontrol.glasses.action.START"
        const val ACTION_STOP = "com.m57.hermescontrol.glasses.action.STOP"
        internal const val ACTION_MIRROR_PHONE = "com.m57.hermescontrol.glasses.action.MIRROR_PHONE"
        internal const val ACTION_MIRROR_RESPONSE = "com.m57.hermescontrol.glasses.action.MIRROR_RESPONSE"
        const val EXTRA_STORED_SESSION_ID = "com.m57.hermescontrol.glasses.extra.STORED_SESSION_ID"
        const val EXTRA_RUNTIME_SESSION_ID = "com.m57.hermescontrol.glasses.extra.RUNTIME_SESSION_ID"
        const val EXTRA_INITIAL_DISPLAY = "com.m57.hermescontrol.glasses.extra.INITIAL_DISPLAY"
        internal const val EXTRA_GENERATION = "com.m57.hermescontrol.glasses.extra.GENERATION"
        internal const val EXTRA_MIRROR_ID = "com.m57.hermescontrol.glasses.extra.MIRROR_ID"
        internal const val EXTRA_DISPLAY_TEXT = "com.m57.hermescontrol.glasses.extra.DISPLAY_TEXT"
        private const val CHANNEL_ID = "myvu_audio"
        private const val NOTIFICATION_ID = 42
        private const val READY_TIMEOUT_MILLIS = 10_000L
        private const val TAG = "HermesMyvuAudio"
    }
}
