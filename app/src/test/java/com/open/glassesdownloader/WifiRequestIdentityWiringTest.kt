package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiRequestIdentityWiringTest {
    private val wifi = File("src/main/java/com/open/glassesdownloader/GlassesWifi.kt").readText()

    @Test fun legacyAndNetworkSpecifierCallbacksUseRequestIdentity() {
        assertTrue(wifi.contains("val token = gate.begin()"))
        assertTrue(wifi.contains("gate.isCurrent(token)"))
        assertTrue(wifi.contains("gate.close()"))
        assertTrue(wifi.contains("requestLocalOnlyNetwork(ssid, token,"))
    }
}
