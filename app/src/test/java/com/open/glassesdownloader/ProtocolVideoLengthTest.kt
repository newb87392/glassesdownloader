package com.open.glassesdownloader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Frames for the device-side video-length setting (BLE command 0x02, seconds, 2-byte big-endian). */
class ProtocolVideoLengthTest {
    private fun hex(text: String): ByteArray {
        val clean = text.replace(" ", "")
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    @Test fun fifteenMinutesMatchesVendorTwoByteFrame() =
        assertArrayEquals(hex("AB55000402038489"), Protocol.videoLength(900))

    @Test fun threeMinutesMatchesVendorTwoByteFrame() =
        assertArrayEquals(hex("AB5500040200B4B6"), Protocol.videoLength(180))

    @Test fun clampsToTheRangeTheFirmwareOffers() {
        assertArrayEquals(Protocol.videoLength(60), Protocol.videoLength(1))
        assertArrayEquals(Protocol.videoLength(900), Protocol.videoLength(7200))
    }

    @Test fun statusRequestAsksForTheCurrentValue() =
        assertArrayEquals(hex("AB550003480048"), Protocol.deviceStatus())

    @Test fun parsesVideoLengthNotification() {
        assertEquals(180, Protocol.videoLengthSeconds(Protocol.parseResponse(hex("AC5500040200B4B6"))!!))
        assertEquals(900, Protocol.videoLengthSeconds(Protocol.parseResponse(hex("AC5500040203 8489"))!!))
    }

    @Test fun capacityProbeMatchesTheVendorCommand() =
        assertArrayEquals(hex("AB550003160016"), Protocol.deviceCapacity())

    @Test fun vendorApJobMatchesTheOfficialAppFrame() =
        assertArrayEquals(hex("AB550003633093"), Protocol.openWifiVendor(false))

    @Test fun vendorP2pJobUsesItsOwnParameter() =
        assertArrayEquals(hex("AB550003633194"), Protocol.openWifiVendor(true))

    @Test fun ignoresOtherCommandsAndImpossibleValues() {
        assertNull(Protocol.videoLengthSeconds(Protocol.parseResponse(hex("AC55000F2544482D5477492D544553543030BF"))!!))
        assertNull(Protocol.videoLengthSeconds(Protocol.parseResponse(hex("AC55000402000002"))!!)) // zero seconds
    }
}
