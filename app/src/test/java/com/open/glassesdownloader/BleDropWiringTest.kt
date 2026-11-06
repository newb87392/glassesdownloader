package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class BleDropWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/GlassesBle.kt").readText()

    @Test fun gattDropAfterSsidFailsWithRecoveryInstruction() {
        val branch = source.substringAfter("if (gotSsid) {").substringBefore("} else fail(token")
        assertTrue(branch.contains("fail(token,"))
        assertTrue(branch.contains("Wi-Fi"))
        assertFalse(branch.contains("may still work"))
    }
}
