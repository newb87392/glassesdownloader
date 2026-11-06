package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring for the Manual / Extra Settings tabs and the video-length control. */
class ExtraSettingsTabsWiringTest {
    private val main = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()
    private val ble = File("src/main/java/com/open/glassesdownloader/GlassesBle.kt").readText()
    private val wifi = File("src/main/java/com/open/glassesdownloader/GlassesWifi.kt").readText()

    @Test fun tabsSplitManualWifiFromDeviceSettings() {
        assertTrue(main.contains("tab(\"Manual\")"))
        assertTrue(main.contains("tab(\"Extra Settings\")"))
        assertTrue(main.contains("private lateinit var manualPane: LinearLayout"))
        assertTrue(main.contains("private lateinit var extraPane: LinearLayout"))
        assertTrue(main.contains("manualPane.addView(manualButton"))
        assertTrue(main.contains("extraPane.addView(styleButton"))
        assertTrue(main.contains("body.addView(manualPane)"))
        assertTrue(main.contains("body.addView(extraPane)"))
        // Tabs sit under the action row, not above it.
        assertTrue(main.indexOf("body.addView(actionRow") < main.indexOf("body.addView(manualPane)"))
    }

    @Test fun videoLengthOffersOnlyFirmwareValues() {
        assertTrue(main.contains("intArrayOf(60, 180, 300, 420, 600, 900)"))
    }

    @Test fun videoLengthIsSentOnlyToAChosenDeviceWithPermission() {
        assertTrue(main.contains("if (selectedAddress == null)"))
        assertTrue(main.contains("ble.setVideoLength(selectedAddress!!"))
        assertTrue(main.contains("REQUEST_VIDEO"))
        assertTrue(ble.contains("fun setVideoLength("))
        assertTrue(ble.contains("Protocol.videoLength(videoSeconds)"))
        // Setting a duration must never become a recording command.
        assertFalse(main.contains("startRecord()"))
        assertFalse(main.contains("stopRecord()"))
    }

    @Test fun currentValueIsReadBackFromTheGlasses() {
        assertTrue(ble.contains("fun readVideoLength("))
        assertTrue(ble.contains("Protocol.deviceStatus()"))
        assertTrue(ble.contains("Protocol.videoLengthSeconds("))
        assertTrue(main.contains("ble.readVideoLength(selectedAddress!!"))
    }

    @Test fun experimentalSectionListsEveryDeviceParamWithItsOptions() {
        assertTrue(main.contains("\"EXPERIMENTAL\""))
        assertTrue(main.contains("button(\"Load device settings\""))
        assertTrue(main.contains("private lateinit var paramsContainer: LinearLayout"))
        assertTrue(main.contains("DeviceParams.parseItems("))
        assertTrue(main.contains("param.items.joinToString"))
        // Writes go through the pure builder; the UI never hand-writes a device URL.
        assertTrue(main.contains("DeviceParams.setPath("))
        assertFalse(main.contains("/app/deletefile"))
        assertFalse(main.contains("enterrecorder"))
    }

    @Test fun capacityProbeIsReadOnlyAndUsesTheVendorCommand() {
        assertTrue(main.contains("button(\"Probe device capacity (BLE 0x16)\""))
        assertTrue(main.contains("ble.probeCapacity(selectedAddress!!"))
        assertTrue(ble.contains("fun probeCapacity("))
        assertTrue(ble.contains("Protocol.deviceCapacity()"))
        // The probe may only carry the capacity request, never a write.
        assertFalse(ble.contains("Protocol.setParam"))
        // A silent probe is one-shot: it must not fall into the reconnect/download path.
        assertTrue(main.contains("styleOperation = true") && main.contains("Probing capacity"))
    }

    @Test fun wifiOpenFallsBackToTheVendorApJob() {
        assertTrue(ble.contains("Protocol.openWifiVendor(requestedP2p)"))
        assertTrue(ble.contains("val vendorJob = attempts > 2"))
    }

    @Test fun changingASettingOffersToStartTheGlassesWifi() {
        // A parameter write needs their web server, so an unreachable device must prompt, not fail.
        assertTrue(main.contains("Start Wi-Fi & apply"))
        assertTrue(main.contains("Start Wi-Fi & load"))
        assertTrue(main.contains("private fun offerToStartWifiForParam("))
        assertTrue(main.contains("private fun startSettingsSession("))
        assertTrue(main.contains("startSettingsSession(SettingsTask.APPLY)"))
        assertTrue(main.contains("SettingsTask.LOAD -> runSettingsLoad(network)"))
        assertTrue(main.contains("SettingsTask.APPLY -> runSettingsApply(network)"))
        // The join itself uses the same AP credentials as a transfer.
        assertTrue(wifi.contains("AP_PASSPHRASE = \"12345678\""))
    }
}
