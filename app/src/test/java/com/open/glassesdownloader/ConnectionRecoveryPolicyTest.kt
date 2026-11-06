package com.open.glassesdownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecoveryPolicyTest {
    @Test fun retriesAtMostTwiceBeforeTransferStarts() {
        assertTrue(ConnectionRecoveryPolicy.shouldRestart(0, false, false))
        assertTrue(ConnectionRecoveryPolicy.shouldRestart(1, false, false))
        assertFalse(ConnectionRecoveryPolicy.shouldRestart(2, false, false))
    }

    @Test fun neverRestartsDuringTransferOrExperimentalStyleWrite() {
        assertFalse(ConnectionRecoveryPolicy.shouldRestart(0, true, false))
        assertFalse(ConnectionRecoveryPolicy.shouldRestart(0, false, true))
    }
}
