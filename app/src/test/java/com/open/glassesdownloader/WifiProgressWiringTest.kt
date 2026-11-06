package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiProgressWiringTest {
    private val wifi = File("src/main/java/com/open/glassesdownloader/GlassesWifi.kt").readText()
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun legacyJoinProgressReachesVisibleLog() {
        assertTrue(wifi.contains("onProgress: (String) -> Unit"))
        assertTrue(wifi.contains("onProgress = { message -> if (gate.isCurrent(token)) onProgress(message) }"))
        assertTrue(activity.contains("onProgress = { message -> if (operations.isCurrent(token) && cycle == wifiCycle) append(message) }"))
    }
}
