package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

class BleLifecycleTest {
    @Test fun replacedSessionRejectsOldEvents() {
        val gate = BleLifecycle()
        val old = gate.begin()
        val current = gate.begin()
        assertFalse(gate.accepts(old))
        assertTrue(gate.accepts(current))
    }

    @Test fun identityAndCloseRejectLateCallbacks() {
        val gate = BleLifecycle()
        val token = gate.begin()
        val owner = Any()
        gate.bind(token, owner)
        assertTrue(gate.accepts(token, owner))
        assertFalse(gate.accepts(token, Any()))
        gate.end()
        assertFalse(gate.accepts(token, owner))
        assertFalse(gate.accepts(token))
    }

    @Test fun deadlineCannotAffectReplacementOrCompletedSetup() {
        val gate = BleLifecycle()
        var errors = 0
        val first = gate.begin()
        val oldTimeout = gate.deadline(first) { errors++ }
        val second = gate.begin()
        oldTimeout()
        assertEquals(0, errors)
        val currentTimeout = gate.deadline(second) { errors++ }
        currentTimeout()
        currentTimeout()
        assertEquals("A deadline fires at most once", 1, errors)
        val completedTimeout = gate.deadline(second) { errors++ }
        gate.cancelDeadline()
        completedTimeout()
        assertEquals(1, errors)
    }

    @Test fun staleDeadlineRegistrationCannotCancelCurrentTimeout() {
        val gate = BleLifecycle()
        val old = gate.begin()
        val current = gate.begin()
        var fired = false
        val timeout = gate.deadline(current) { fired = true }
        gate.deadline(old) { fail("Old deadline must never run") }()
        timeout()
        assertTrue("Stale work cannot disarm the new session's deadline", fired)
    }

    @Test fun staleGattCannotBindToReplacementSessionEvenWithSameAddress() {
        val gate = BleLifecycle()
        // Equal values simulate separate GATT objects for the same device address.
        data class Gatt(val address: String)
        val oldGatt = Gatt("AA:BB:CC:DD:EE:FF")
        val newGatt = Gatt(oldGatt.address)
        val old = gate.begin()
        gate.bind(old, oldGatt)
        val current = gate.begin()
        gate.bind(current, newGatt)
        gate.bind(old, oldGatt)
        assertFalse(gate.accepts(current, oldGatt))
        assertFalse(gate.accepts(old, newGatt))
        assertTrue(gate.accepts(current, newGatt))
    }

    @Test fun closeInvalidatesAlreadyQueuedTimeout() {
        val gate = BleLifecycle()
        val token = gate.begin()
        val timeout = gate.deadline(token) { fail("Closed session timed out") }
        gate.end()
        timeout()
    }

    @Test fun advancingSetupReplacesPreviousPhaseDeadline() {
        val gate = BleLifecycle()
        val token = gate.begin()
        val failures = mutableListOf<String>()
        val connect = gate.deadline(token) { failures += "connect" }
        val services = gate.deadline(token) { failures += "services" }
        val notifications = gate.deadline(token) { failures += "notifications" }
        connect()
        services()
        notifications()
        assertEquals(listOf("notifications"), failures)
    }
}
