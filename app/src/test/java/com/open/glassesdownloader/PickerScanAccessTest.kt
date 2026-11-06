package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

class PickerScanAccessTest {
    private fun runGate(missing: () -> Array<String>, request: (Array<String>) -> Unit,
                        denied: () -> Unit, scan: () -> Unit) {
        val type = runCatching { Class.forName("com.open.glassesdownloader.PickerScanAccess") }.getOrNull()
        assertNotNull("Every scan entry needs a permission and SecurityException boundary", type)
        type!!.getMethod("run", kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function1::class.java,
            kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java)
            .invoke(type.getField("INSTANCE").get(null), missing, request, denied, scan)
    }

    @Test fun deniedScanRequestsPermissionsWithoutTouchingBluetooth() {
        var requested = emptyArray<String>()
        var scanned = false
        runGate({ arrayOf("scan") }, { requested = it }, { fail("Unexpected revocation") }, { scanned = true })
        assertArrayEquals(arrayOf("scan"), requested)
        assertFalse(scanned)
    }

    @Test fun revokedPermissionDuringBluetoothAccessDoesNotEscape() {
        var denied = false
        runGate({ emptyArray() }, { fail("Already granted") }, { denied = true }, { throw SecurityException("revoked") })
        assertTrue(denied)
    }

    @Test fun grantedScanRunsExactlyOnce() {
        var scans = 0
        runGate({ emptyArray() }, { fail("Already granted") }, { fail("No revocation") }, { scans++ })
        assertEquals(1, scans)
    }
}
