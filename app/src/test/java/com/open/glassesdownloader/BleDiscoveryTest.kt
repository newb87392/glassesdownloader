package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

class BleDiscoveryTest {
    @Test fun namePrefixNarrowsServiceDiscovery() {
        assertFalse(BleDiscovery.matches(false, "Glasses", "Glass"))
        assertFalse(BleDiscovery.matches(true, "Other", "Glass"))
        assertTrue(BleDiscovery.matches(true, "GLASSES", " glass "))
        assertTrue(BleDiscovery.matches(true, "", ""))
        assertFalse(BleDiscovery.matches(false, "", ""))
    }
}
