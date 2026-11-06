package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WifiWarmupWiringTest {
    private val main = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun ssidReplyStartsWarmupInsteadOfJoiningImmediately() {
        val callback = main.substringAfter("onSsid = { found ->").substringBefore("onError =")
        assertTrue("SSID callback should schedule a warm-up", callback.contains("startWifiWarmup(found)"))
        assertFalse("SSID callback must not request Wi-Fi immediately", callback.contains("requestGlassesWifi(found)"))
    }

    @Test fun warmupWaitsFiveSecondsAfterSsidReplyAndShowsCountdown() {
        assertTrue(main.contains("WIFI_WARMUP_MS = 5000L"))
        assertTrue(main.contains("SystemClock.elapsedRealtime()"))
        assertTrue(main.contains("Wi-Fi starting in"))
    }

    @Test fun scheduledWarmupIsCancelledOnOperationEnd() {
        val finish = main.substringAfter("private fun finishOperation(title: String)").substringBefore("private fun fail(")
        assertTrue(finish.contains("cancelWifiWarmup()"))
    }
}
