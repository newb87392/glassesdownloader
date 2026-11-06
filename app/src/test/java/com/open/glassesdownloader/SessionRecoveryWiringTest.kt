package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecoveryWiringTest {
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun bothWifiJoinAndBleSetupFailuresHaveBoundedSessionRecovery() {
        assertTrue(activity.contains("private fun retryBleSession("))
        assertTrue(activity.contains("ConnectionRecoveryPolicy.shouldRestart("))
        assertTrue(activity.contains("onError = { handleBleError(it) }"))
        val failure = activity.substringAfter("onUnavailable = { reason ->").substringBefore("onProgress =")
        assertTrue(failure.contains("retryBleSession("))
    }
}
