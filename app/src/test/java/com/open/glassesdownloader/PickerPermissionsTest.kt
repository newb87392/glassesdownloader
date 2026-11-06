package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

class PickerPermissionsTest {
    private fun permissions(method: String, sdk: Int): Set<String> {
        val api = Permissions::class.java.methods.firstOrNull {
            it.name == method && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
        }
        assertNotNull("Missing separate $method permission policy", api)
        @Suppress("UNCHECKED_CAST")
        return (api!!.invoke(Permissions, sdk) as Array<String>).toSet()
    }

    @Test fun pickerNeverRequestsWifiOrModernLocation() {
        for (sdk in listOf(31, 32, 33, 35)) {
            assertEquals(setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), permissions("bluetoothRequired", sdk))
        }
    }

    @Test fun legacyPickerKeepsLocationAndTransferKeepsWifiPermissions() {
        val location = setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        for (sdk in listOf(26, 28, 29, 30)) assertEquals(location, permissions("bluetoothRequired", sdk))
        for (sdk in listOf(26, 30, 31, 32)) assertEquals(location, permissions("wifiRequired", sdk))
        for (sdk in listOf(33, 35)) assertEquals(setOf("android.permission.NEARBY_WIFI_DEVICES"), permissions("wifiRequired", sdk))
    }
}
