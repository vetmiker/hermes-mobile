package com.m57.hermescontrol.glasses.myvu

import org.junit.Assert.assertEquals
import org.junit.Test

class MyvuProtocolTest {
    @Test
    fun bindsTheInstalledInternationalStockServiceContract() {
        assertEquals("com.upuphone.star.launcher.intl", MyvuProtocol.STOCK_PACKAGE)
        assertEquals("com.upuphone.xr.interconnect.CommonService", MyvuProtocol.COMMON_SERVICE_ACTION)
        assertEquals("com.upuphone.star.launcher", MyvuProtocol.LAUNCHER_RECEIVER)
        assertEquals(2, MyvuProtocol.MESSAGE_TRANSPORT_SERVICE)
    }
}
