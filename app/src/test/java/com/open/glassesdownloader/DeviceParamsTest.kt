package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers for the hardware settings API, against the reply the CT01 actually returned. */
class DeviceParamsTest {
    private val items = """
        {"result":0,"info":[
        {"name":"mic","items":["off","on"],"index":[0,1]},
        {"name":"osd","items":["off","on"],"index":[0,1]},
        {"name":"rec_resolution","items":["2K","4K"],"index":[0,1]},
        {"name":"rec_split_duration","items":["off","1MIN","2MIN","3MIN"],"index":[0,1,2,3]},
        {"name":"park_record_time","items":["off","12hour","24hour","48hour"],"index":[0,1,2,3]},
        {"name":"language","items":["en_US","zh_CN","zh_CHT","ja","ko","ru","fr","de"],"index":[0,1,2,3,4,5,6,7]}]}
    """.trimIndent()

    @Test fun parsesTheDeviceSettingsList() {
        val parsed = DeviceParams.parseItems(items)
        assertEquals(6, parsed.size)
        assertEquals(listOf("mic", "osd", "rec_resolution", "rec_split_duration", "park_record_time", "language"),
            parsed.map { it.name })
        assertEquals(listOf("off", "on"), parsed[0].items)
        assertEquals(listOf("2K", "4K"), parsed[2].items)
        assertEquals(listOf("off", "1MIN", "2MIN", "3MIN"), parsed[3].items)
        assertEquals(8, parsed[5].items.size)
    }

    @Test fun mapsLabelsToWireValues() {
        val split = DeviceParams.parseItems(items).first { it.name == "rec_split_duration" }
        assertEquals(3, split.wireValueAt(3))
        assertEquals("3MIN", split.labelFor(3))
        assertEquals("—", split.labelFor(900)) // 15 min is BLE-only, not in this table
    }

    @Test fun parsesSingleAndWholeValueReplies() {
        assertEquals(2, DeviceParams.parseValue("""{"result":0,"info":{"value":2}}"""))
        assertEquals(0, DeviceParams.parseValue("""{"result":0,"info":0}"""))
        assertNull(DeviceParams.parseValue("""{"result":0,"info":[]}"""))
        assertEquals(mapOf("mic" to 1, "osd" to 0),
            DeviceParams.parseValues("""{"result":0,"info":{"mic":1,"osd":0}}"""))
        assertEquals(emptyMap<String, Int>(), DeviceParams.parseValues("""{"result":0,"info":{"value":1}}"""))
    }

    @Test fun parsesTheArrayOfValuesShapeThisFirmwareUses() {
        val payload = """{"result":0,"info":[{"name":"mic","value":1},{"name":"timelapse_rate","value":1},""" +
            """{"name":"rec_split_duration","value":1},{"name":"auto_poweroff","value":0}]}"""
        val values = DeviceParams.parseValues(payload)
        assertEquals(1, values["mic"])
        assertEquals(1, values["timelapse_rate"])
        assertEquals(1, values["rec_split_duration"])
        assertEquals(0, values["auto_poweroff"])
    }

    @Test fun detectsAcceptedWrite() {
        assertTrue(DeviceParams.setAccepted("""{"result":0}"""))
        assertFalse(DeviceParams.setAccepted("""{"result":98}"""))
    }

    @Test fun urlsOnlyAddressTheParameterEndpoints() {
        assertEquals("http://192.168.169.1/app/getparamitems?param=all", DeviceParams.itemsUrl("192.168.169.1"))
        assertEquals("http://192.168.169.1/app/getparamvalue?param=mic", DeviceParams.valueUrl("192.168.169.1", "mic"))
        assertEquals("http://192.168.169.1/app/setparamvalue?param=mic&value=1",
            DeviceParams.setUrl("192.168.169.1", "mic", 1))
        // Guardrail: this surface must never grow a record/delete/format request.
        val built = listOf(
            DeviceParams.itemsPath(), DeviceParams.valuePath("mic"), DeviceParams.setPath("mic", 1)
        ).joinToString(" ")
        for (danger in listOf("deletefile", "custom=", "record", "format", "ota", "enterrecorder")) {
            assertFalse("settings surface must not mention $danger", built.contains(danger))
        }
    }
}
