package com.m57.hermescontrol.glasses.myvu

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test

/**
 * Manually invoked U3 hardware gate. The visible Activity owns the permission
 * prompt and foreground-service start; this test never starts the service.
 */
class MyvuAudioDiagnosticActivityTest {
    @Test
    fun startsAudioFromTheVisibleDiagnosticActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent().setClassName(context, "com.m57.hermescontrol.glasses.myvu.MyvuDiagnosticActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(2_000)
    }
}
