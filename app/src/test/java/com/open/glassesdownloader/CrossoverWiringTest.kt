package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CrossoverWiringTest {
    private val main = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun diagnosticCanChangeHoldTimeToCheckForFixedApTimeout() {
        assertTrue(main.contains("EXTRA_DIAGNOSTIC_HOLD_SECONDS"))
        val section = main.substringAfter("private fun runBleWifiCrossover(network: Network, token: Long, workerCancellation: AtomicBoolean, transferFailed: Boolean)")
            .substringBefore("private fun stop()")
        assertTrue(section.contains("intent.getIntExtra(EXTRA_DIAGNOSTIC_HOLD_SECONDS, 90)"))
        assertTrue(section.contains("if (phase == \"BLE_HELD\") holdSeconds * 1_000L else 90_000L"))
    }

    @Test fun diagnosticIsOptInAndNormalTransferStillFinishes() {
        assertTrue(main.contains("EXTRA_DIAGNOSTIC_CROSSOVER"))
        assertTrue(main.contains("runBleWifiCrossover(network, token, workerCancellation, result.failed > 0)"))
        assertTrue(main.contains("finishOperation(if (result.failed > 0)"))
    }

    @Test fun bothPhasesProbeTheSameNetworkWithoutRejoining() {
        val section = main.substringAfter("private fun runBleWifiCrossover(network: Network, token: Long, workerCancellation: AtomicBoolean, transferFailed: Boolean)")
            .substringBefore("private fun stop()")
        assertTrue(section.contains("network.openConnection("))
        assertTrue(section.contains("ble.close()"))
        assertFalse(section.contains("wifi.request("))
        assertTrue(section.contains("90_000L"))
        assertTrue(section.contains("5_000L"))
    }

    @Test fun probesRemainFiveSecondsApartAcrossPhaseBoundary() {
        val section = main.substringAfter("private fun runBleWifiCrossover(network: Network, token: Long, workerCancellation: AtomicBoolean, transferFailed: Boolean)")
            .substringBefore("private fun stop()")
        assertTrue(section.contains("for (slot in 0 until (phaseDuration / 5_000L).toInt())"))
        assertTrue(section.contains("phaseStart + phaseDuration"))
    }

    @Test fun diagnosticCanDetectSpontaneousGattLoss() {
        val ble = File("src/main/java/com/open/glassesdownloader/GlassesBle.kt").readText()
        assertTrue(ble.contains("fun isSessionActive(): Boolean"))
        assertTrue(main.contains("ble.isSessionActive()"))
    }

    @Test fun diagnosticWritesProgressAsAPrivateFile() {
        val section = main.substringAfter("private fun runBleWifiCrossover(network: Network, token: Long, workerCancellation: AtomicBoolean, transferFailed: Boolean)")
            .substringBefore("private fun stop()")
        assertTrue(section.contains("File(filesDir, \"diagnostics\")"))
        assertTrue(section.contains("writer.flush()"))
    }
}
