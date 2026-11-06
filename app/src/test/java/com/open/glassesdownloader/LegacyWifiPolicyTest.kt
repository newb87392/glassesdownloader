package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyWifiPolicyTest {
    @Test fun reusesSavedNetworkForSameSsid() {
        val saved = listOf("\"Home\"" to 4, "\"Glasses-EXAMPLE1\"" to 1)
        assertEquals(1, LegacyWifiPolicy.existingNetworkId("Glasses-EXAMPLE1", saved))
    }

    @Test fun missingNetworkNeedsNewConfiguration() {
        assertEquals(null, LegacyWifiPolicy.existingNetworkId("Glasses", listOf("\"Home\"" to 4)))
    }

    @Test fun scanningWithMatchingSsidIsNotConnected() {
        assertEquals(false, LegacyWifiPolicy.isJoined("Glasses", "\"Glasses\"", false, 0))
    }

    @Test fun associationWithoutAddressIsNotJoined() {
        assertEquals(false, LegacyWifiPolicy.isJoined("Glasses", "\"Glasses\"", true, 0))
    }

    @Test fun completedAssociationWithAddressIsJoined() {
        assertEquals(true, LegacyWifiPolicy.isJoined("Glasses", "\"Glasses\"", true, 123))
    }
}
