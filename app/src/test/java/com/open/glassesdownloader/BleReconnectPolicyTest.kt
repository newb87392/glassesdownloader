package com.open.glassesdownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleReconnectPolicyTest {
    @Test fun androidGATT133BeforeWifiGetsTwoBoundedRetries() {
        assertTrue(BleReconnectPolicy.shouldRetry(133, false, 0))
        assertTrue(BleReconnectPolicy.shouldRetry(133, false, 1))
        assertFalse(BleReconnectPolicy.shouldRetry(133, false, 2))
    }

    @Test fun neverReconnectsAfterWifiStartsOrForOtherErrors() {
        assertFalse(BleReconnectPolicy.shouldRetry(133, true, 0))
        assertFalse(BleReconnectPolicy.shouldRetry(8, false, 0))
    }
}
