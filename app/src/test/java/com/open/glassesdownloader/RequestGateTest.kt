package com.open.glassesdownloader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestGateTest {
    @Test fun previousRequestCannotCallBackAfterNextBegins() {
        val gate = RequestGate()
        val first = gate.begin()
        assertTrue(gate.isCurrent(first))
        val second = gate.begin()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test fun closeRejectsEvenAlreadyQueuedCallbacks() {
        val gate = RequestGate()
        val token = gate.begin()
        gate.close()
        assertFalse(gate.isCurrent(token))
    }
}
