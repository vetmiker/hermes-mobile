package com.m57.hermescontrol.glasses.myvu

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyvuDiagnosticActivityTest {
    @Before
    fun setUp() {
        MyvuDiagnosticGate.reset()
        MyvuDiagnosticTransportFactory.resetForTesting()
    }

    @After
    fun tearDown() {
        MyvuDiagnosticTransportFactory.resetForTesting()
        MyvuDiagnosticGate.reset()
    }

    @Test
    fun sendsTheU2MarkerThroughTheInjectedDiagnosticTransport() {
        val transport = RecordingDiagnosticTransport()
        MyvuDiagnosticTransportFactory.installForTesting { transport }

        startMarkerActivity(HERMETIC_MARKER)

        waitUntil {
            MyvuDiagnosticGate.result == MyvuDiagnosticGate.Result.Delivered &&
                transport.unbindCount == 1
        }

        assertTrue(transport.readyStateWasObserved)
        assertEquals(1, transport.bindCount)
        assertEquals(3, transport.commands.size)
        assertTrue(transport.commands[0].payload.contains("\"open_app\""))
        assertTrue(transport.commands[1].payload.contains(HERMETIC_MARKER))
        assertTrue(transport.commands[2].fontMode != null)
        assertEquals(1, transport.unbindCount)
    }

    @Test
    fun sendsTheU2MarkerThroughTheInstalledStockTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            "Stock MYVU package is not installed on this device",
            runCatching {
                context.packageManager.getApplicationInfo(MyvuProtocol.STOCK_PACKAGE, 0)
            }.isSuccess,
        )

        startMarkerActivity(PHYSICAL_MARKER)

        waitUntil { MyvuDiagnosticGate.result != MyvuDiagnosticGate.Result.Idle }

        assertEquals(MyvuDiagnosticGate.Result.Delivered, MyvuDiagnosticGate.result)
    }

    private fun startMarkerActivity(marker: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent().setClassName(context, "com.m57.hermescontrol.glasses.myvu.MyvuDiagnosticActivity")
                .putExtra(MyvuDiagnosticGate.EXTRA_MARKER, marker)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + MyvuDiagnosticGate.READY_TIMEOUT_MILLIS
        while (!condition() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
        assertTrue("Diagnostic activity did not finish before timeout", condition())
    }

    private class RecordingDiagnosticTransport : MyvuDiagnosticTransport {
        private val backingState = MutableStateFlow<MyvuTransportState>(MyvuTransportState.Idle)

        override val state: StateFlow<MyvuTransportState>
            get() {
                readyStateWasObserved = true
                return backingState
            }

        var bindCount = 0
            private set
        var readyStateWasObserved = false
            private set
        val commands = mutableListOf<MyvuDisplayCommand>()
        var unbindCount = 0
            private set

        override fun bind(): Boolean {
            bindCount += 1
            backingState.value = MyvuTransportState.Ready
            return true
        }

        override fun send(command: MyvuDisplayCommand): Result<String?> {
            commands += command
            return Result.success(null)
        }

        override fun unbind() {
            unbindCount += 1
            backingState.value = MyvuTransportState.Idle
        }
    }

    private companion object {
        const val HERMETIC_MARKER = "HERMES-MYVU-U2-HERMETIC"
        const val PHYSICAL_MARKER = "HERMES-MYVU-U2-20260817-001"
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
