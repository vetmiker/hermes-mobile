package com.m57.hermescontrol.glasses.service

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MyvuGlassesServiceTest {
    @Test
    fun declaresMicrophoneForegroundServiceType() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, MyvuGlassesService::class.java),
                PackageManager.ComponentInfoFlags.of(0),
            )

        assertNotEquals(0, info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }
}
