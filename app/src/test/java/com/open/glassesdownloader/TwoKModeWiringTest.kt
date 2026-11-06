package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TwoKModeWiringTest {
    private val ble = File("src/main/java/com/open/glassesdownloader/GlassesBle.kt").readText()
    private val main = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun twoKOnlySendsStyleAfterGATTIsReadyInsteadOfOpeningWifi() {
        assertTrue(ble.contains("fun startStyle2k("))
        assertTrue(ble.contains("if (style2k) sendStyle2k(token, g) else sendOpenWifi(token, g)"))
        assertTrue(ble.contains("Protocol.cameraStyle2k()"))
        assertTrue(ble.contains("onCharacteristicWrite"))
    }

    @Test fun twoKRequiresExplicitDeviceAndUserAction() {
        assertTrue(main.contains("button(\"Try 2K / 16:9 video mode (experimental)\""))
        assertTrue(main.contains("if (selectedAddress == null)"))
        assertTrue(main.contains("ble.startStyle2k(selectedAddress!!"))
        assertFalse(main.contains("startRecord()"))
        assertFalse(main.contains("stopRecord()"))
    }
}
