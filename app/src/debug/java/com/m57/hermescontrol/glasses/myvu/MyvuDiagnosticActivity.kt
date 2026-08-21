package com.m57.hermescontrol.glasses.myvu

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import com.m57.hermescontrol.BuildConfig
import com.m57.hermescontrol.glasses.service.MyvuGlassesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object MyvuDiagnosticGate {
    const val EXTRA_MARKER = "com.m57.hermescontrol.glasses.myvu.extra.MARKER"
    const val READY_TIMEOUT_MILLIS = 10_000L

    sealed interface Result {
        data object Idle : Result

        data object Delivered : Result

        data class Failed(val reason: String) : Result
    }

    @Volatile
    var result: Result = Result.Idle
        private set

    fun reset() {
        result = Result.Idle
    }

    fun failed(reason: String) {
        result = Result.Failed(reason)
    }

    fun delivered() {
        result = Result.Delivered
    }
}

interface MyvuDiagnosticTransport {
    val state: StateFlow<MyvuTransportState>

    fun bind(): Boolean

    fun send(command: MyvuDisplayCommand): Result<String?>

    fun unbind()
}

object MyvuDiagnosticTransportFactory {
    private val stockFactory: (Context) -> MyvuDiagnosticTransport = { context ->
        StockMyvuDiagnosticTransport(context)
    }

    @Volatile
    private var factory: (Context) -> MyvuDiagnosticTransport = stockFactory

    fun create(context: Context): MyvuDiagnosticTransport = factory(context)

    fun installForTesting(factory: () -> MyvuDiagnosticTransport) {
        this.factory = { factory() }
    }

    fun resetForTesting() {
        factory = stockFactory
    }
}

private class StockMyvuDiagnosticTransport(
    context: Context,
) : MyvuDiagnosticTransport {
    private val transport = MyvuTransport(context)

    override val state: StateFlow<MyvuTransportState> = transport.state

    override fun bind(): Boolean = transport.bind()

    override fun send(command: MyvuDisplayCommand): Result<String?> = transport.send(command)

    override fun unbind() {
        transport.unbind()
    }
}

/**
 * USB-only U2 gate. This component is compiled into debug builds only and is
 * unexported, so neither a host nor another app can start MYVU transport.
 */
class MyvuDiagnosticActivity : Activity() {
    private val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var transport: MyvuDiagnosticTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_START_AUDIO, false)) {
            val audioToken = BuildConfig.MYVU_BRIDGE_TOKEN
            if (audioToken.isBlank()) {
                MyvuDiagnosticGate.failed("MYVU_BRIDGE_TOKEN is not configured for this build")
                Log.e(TAG, "MYVU_AUDIO_DIAGNOSTIC start refused tokenConfigured=false")
                finish()
            } else {
                startAudioAfterVisiblePermission(audioToken)
            }
            return
        }
        if (intent.getBooleanExtra(EXTRA_STOP_AUDIO, false)) {
            stopService(Intent(this, MyvuGlassesService::class.java).setAction(MyvuGlassesService.ACTION_STOP))
            finish()
            return
        }
        val marker = intent.getStringExtra(MyvuDiagnosticGate.EXTRA_MARKER)
        if (marker.isNullOrBlank()) {
            MyvuDiagnosticGate.failed("Missing diagnostic marker")
            Log.e(TAG, "Missing diagnostic marker")
            finish()
            return
        }

        transport = MyvuDiagnosticTransportFactory.create(this)
        if (!transport.bind()) {
            MyvuDiagnosticGate.failed("Stock MYVU bind request was rejected")
            Log.e(TAG, "Stock MYVU bind request was rejected")
            finish()
            return
        }

        diagnosticScope.launch {
            val ready =
                withTimeoutOrNull(MyvuDiagnosticGate.READY_TIMEOUT_MILLIS) {
                    transport.state.filterIsInstance<MyvuTransportState.Ready>().first()
                }
            if (ready == null) {
                MyvuDiagnosticGate.failed("Stock MYVU transport did not become ready")
                Log.e(TAG, "Stock MYVU transport did not become ready")
                finish()
                return@launch
            }
            val commands = MyvuDisplayRenderer().commandsFor(marker, DisplayKind.Response)
            val results = commands.map(transport::send)
            if (results.all(Result<String?>::isSuccess)) {
                MyvuDiagnosticGate.delivered()
                Log.i(TAG, "MYVU_DIAG_MARKER delivered commandCount=${commands.size}")
            } else {
                MyvuDiagnosticGate.failed("MYVU display delivery failed")
                Log.e(TAG, "MYVU_DIAG_MARKER delivery failed")
            }
            finish()
        }
    }

    override fun onDestroy() {
        diagnosticScope.cancel()
        if (::transport.isInitialized) transport.unbind()
        super.onDestroy()
    }

    private fun startAudioAfterVisiblePermission(token: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAudio(token)
        } else {
            pendingAudioToken = token
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val token = pendingAudioToken
        pendingAudioToken = null
        if (
            requestCode == REQUEST_RECORD_AUDIO &&
            token != null &&
            grantResults.singleOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startAudio(token)
        } else {
            MyvuDiagnosticGate.failed("Microphone permission was not granted")
            finish()
        }
    }

    private fun startAudio(token: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, MyvuGlassesService::class.java)
                .setAction(MyvuGlassesService.ACTION_START)
                .putExtra(MyvuGlassesService.EXTRA_AUDIO_TOKEN, token)
                .putExtra(MyvuGlassesService.EXTRA_STORED_SESSION_ID, DIAGNOSTIC_SESSION_ID)
                .putExtra(MyvuGlassesService.EXTRA_RUNTIME_SESSION_ID, DIAGNOSTIC_SESSION_ID)
                .putExtra(MyvuGlassesService.EXTRA_INITIAL_DISPLAY, DIAGNOSTIC_INITIAL_DISPLAY),
        )
        Log.i(TAG, "MYVU_AUDIO_DIAGNOSTIC start requested")
        finish()
    }

    private var pendingAudioToken: String? = null

    private companion object {
        const val TAG = "HermesMyvuDiag"
        const val EXTRA_STOP_AUDIO = "com.m57.hermescontrol.glasses.myvu.extra.STOP_AUDIO"
        const val EXTRA_START_AUDIO = "com.m57.hermescontrol.glasses.myvu.extra.START_AUDIO"
        const val REQUEST_RECORD_AUDIO = 1
        const val DIAGNOSTIC_SESSION_ID = "myvu-audio-diagnostic"
        const val DIAGNOSTIC_INITIAL_DISPLAY = "MYVU audio diagnostic"
    }
}
