package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source contracts complement pure JVM tests; no device framework is mocked. */
class PickerWiringTest {
    private fun source(name: String) = File("src/main/java/com/open/glassesdownloader/$name.kt").readText()

    @Test fun pickerQueriesPublicGattApiAndUsesBluetoothOnlyGate() {
        val picker = source("DevicePickerActivity")
        assertTrue(picker.contains("manager.getConnectedDevices(BluetoothProfile.GATT)"))
        assertTrue(picker.contains("PickerScanAccess.run("))
        assertTrue(picker.contains("Permissions.missingBluetooth(this)"))
        assertFalse(picker.contains("Permissions.missing(this)"))
        assertTrue(picker.contains("entry.statusLabel"))
    }

    @Test fun tapUsesBoundIdentityInsteadOfResortingLiveResults() {
        val picker = source("DevicePickerActivity")
        assertFalse(picker.contains("sortedEntries()"))
        assertTrue(picker.contains("row.tag = entry"))
        assertTrue(picker.contains("view.tag as? PickerDevices.Entry"))
        assertTrue(picker.contains("override fun hasStableIds(): Boolean = true"))
    }

    @Test fun mainPickerDoesNotRequestWifiPermissionsAndHasCorrectLabel() {
        val main = source("MainActivity")
        assertTrue(main.contains("button(\"Choose Bluetooth device\""))
        val pick = main.substringAfter("private fun pickDevice()").substringBefore("private fun openDevicePicker()")
        assertTrue(pick.contains("Permissions.missingBluetooth(this)"))
    }
}
