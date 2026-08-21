package com.m57.hermescontrol.glasses.myvu

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MyvuDiagnosticActivityTest {
    @Test
    fun sendsTheU2MarkerThroughTheInstalledStockTransport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MyvuDiagnosticGate.reset()

        context.startActivity(
            Intent().setClassName(context, "com.m57.hermescontrol.glasses.myvu.MyvuDiagnosticActivity")
                .putExtra(MyvuDiagnosticGate.EXTRA_MARKER, "HERMES-MYVU-U2-20260817-001")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        SystemClock.sleep(MyvuDiagnosticGate.READY_TIMEOUT_MILLIS + 1_000L)

        assertEquals(MyvuDiagnosticGate.Result.Delivered, MyvuDiagnosticGate.result)
    }
}
