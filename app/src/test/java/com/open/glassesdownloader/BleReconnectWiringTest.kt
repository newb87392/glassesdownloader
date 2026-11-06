package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectWiringTest {
    private val ble = File("src/main/java/com/open/glassesdownloader/GlassesBle.kt").readText()

    @Test fun status133ClosesStaleGattAndRetriesWithNewSession() {
        val branch = ble.substringAfter("override fun onConnectionStateChange").substringBefore("override fun onServicesDiscovered")
        assertTrue(branch.contains("BleReconnectPolicy.shouldRetry("))
        assertTrue(branch.contains("retryGatt("))
        assertTrue(ble.contains("Bluetooth retry"))
    }
}
