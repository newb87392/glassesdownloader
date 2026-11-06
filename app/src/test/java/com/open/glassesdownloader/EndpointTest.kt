package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

/** The two firmware dialects differ in both host and path layout; pin the exact URLs. */
class EndpointTest {
    private val tSeries = Endpoint.ALL[0]
    private val legacy = Endpoint.ALL[1]

    @Test fun indexSizeUnitsDifferBetweenFirmwareFamilies() {
        assertEquals(MediaIndex.SizeUnit.KIB_FLOOR, tSeries.sizeUnit)
        assertEquals(MediaIndex.SizeUnit.BYTES, legacy.sizeUnit)
    }

    @Test fun tSeriesUsesTheAppRestApi() {
        assertEquals("http://192.168.169.1/app/getfilelist", tSeries.indexUrl())
    }

    @Test fun legacyUsesTheCgiListing() {
        assertEquals("http://192.168.49.207/?custom=1&cmd=3015", legacy.indexUrl())
    }

    @Test fun tSeriesDeletesThroughTheAppRestApi() {
        assertEquals("http://192.168.169.1/app/deletefile?file=LOOP/20260101010101000.mp4",
            tSeries.deleteUrl("LOOP/20260101010101000.mp4"))
    }

    @Test fun legacyDeletesThroughTheVendorCgi() {
        assertEquals("http://192.168.49.207/?custom=1&cmd=4003&str=A:" + 92.toChar() + "DCIM" +
            92.toChar() + "100HUNTI" + 92.toChar() + "100_0001.JPG",
            legacy.deleteUrl("100_0001.JPG"))
    }

    @Test fun tSeriesKeepsTheReportedRelativePath() {
        assertEquals("http://192.168.169.1/EVENT/20260101010101000.jpg",
            tSeries.fileUrl("EVENT/20260101010101000.jpg"))
    }

    @Test fun legacyAddsTheDcimPrefixToBareNames() {
        assertEquals("http://192.168.49.207/DCIM/100HUNTI/100_0001.JPG",
            legacy.fileUrl("100_0001.JPG"))
    }

    @Test fun legacyDoesNotDoubleAnAlreadyQualifiedPath() {
        assertEquals("http://192.168.49.207/DCIM/100HUNTI/100_0001.JPG",
            legacy.fileUrl("DCIM/100HUNTI/100_0001.JPG"))
    }

    @Test fun tSeriesNeverInventsAFolder() {
        assertEquals("http://192.168.169.1/100_0001.JPG", tSeries.fileUrl("100_0001.JPG"))
    }
}
