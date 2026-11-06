package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test

/** Reflection keeps the RED phase an assertion failure before the model exists. */
class PickerDevicesTest {
    private fun model(): Any {
        val type = runCatching { Class.forName("com.open.glassesdownloader.PickerDevices") }.getOrNull()
        assertNotNull("Picker needs an immutable displayed-row snapshot", type)
        return type!!.getDeclaredConstructor().newInstance()
    }
    private fun update(model: Any, address: String, rssi: Int, paired: Boolean = false, connected: Boolean = false) {
        model.javaClass.getMethod("update", String::class.java, String::class.java, Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .invoke(model, address, address, rssi, paired, connected)
    }
    private fun rows(model: Any): List<*> = model.javaClass.getMethod("snapshot").invoke(model) as List<*>
    private fun address(row: Any?) = row!!.javaClass.getMethod("getAddress").invoke(row)

    @Test fun rssiUpdateCannotChangeBoundRowIdentity() {
        val model = model()
        update(model, "A", -40)
        update(model, "B", -70)
        val displayed = rows(model)
        val tappedRow = displayed[0]
        update(model, "B", -20)
        assertEquals("A", address(tappedRow))
        assertEquals("A", address(displayed[0]))
        assertEquals("B", address(rows(model)[0]))
    }

    @Test fun connectedPairedAndNearbyAreDistinctAndDeduplicated() {
        val model = model()
        update(model, "nearby", -20)
        update(model, "paired", -127, paired = true)
        update(model, "connected", -127, connected = true)
        update(model, "connected", -80)
        val rows = rows(model)
        assertEquals(listOf("connected", "paired", "nearby"), rows.map { address(it) })
        assertEquals(listOf("connected (GATT)", "paired", "nearby"), rows.map {
            it!!.javaClass.getMethod("getStatusLabel").invoke(it)
        })
    }
}
