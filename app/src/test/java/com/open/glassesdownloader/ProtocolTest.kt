package com.open.glassesdownloader

// The DH-TwI-TEST00 names below are synthetic frame fixtures, not a real access point.

import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun apCommandMatchesCapturedPacket() = assertArrayEquals(
        hex("AB550003393069"), Protocol.openWifi(false)
    )
    @Test fun p2pCommandMatchesCapturedPacket() = assertArrayEquals(
        hex("AB55000339316A"), Protocol.openWifi(true)
    )
    @Test fun twoKStyleMatchesVendorTwoParameterFrame() = assertArrayEquals(
        hex("AB55000435303095"), Protocol.cameraStyle2k()
    )
    @Test fun extractsRealSsidFromObservedBleNotification() {
        assertEquals("DH-TwI-TEST00", Protocol.ssid(hex("AC55000F2544482D5477492D544553543030BF")))
    }
    @Test fun rejectsIncorrectChecksum() {
        assertNull(Protocol.ssid(hex("AC55000F2544482D5477492D54455354303000")))
    }
    @Test fun stripsChunkSizeLinesFromObservedFileListFormat() {
        val text = "2f\r\n{\"result\":0,\"info\":[\r\n5b\r\n{\"x\":1}\r\n0\r\n]}\r\n0\r\n"
        assertEquals("{\"result\":0,\"info\":[{\"x\":1}]}", FileListCleaner.clean(text).replace(Regex("\\s+"), ""))
    }

    // A 20-byte ATT payload splits any frame with an SSID longer than 14 characters; the
    // assembler must rebuild it instead of dropping both halves.
    @Test fun reassemblesFrameSplitAcrossNotifications() {
        val frame = hex("AC55000F2544482D5477492D544553543030BF")
        val assembler = FrameAssembler()
        assertTrue(assembler.feed(frame.copyOfRange(0, 7)).isEmpty())
        val response = assembler.feed(frame.copyOfRange(7, frame.size)).single()
        assertEquals(Protocol.WIFI_INFO, response.command)
        assertEquals("DH-TwI-TEST00", Protocol.ssid(response))
    }
    @Test fun reassemblesFrameWhenTheHeaderByteStraddlesNotifications() {
        val frame = hex("AC55000F2544482D5477492D544553543030BF")
        val assembler = FrameAssembler()
        assertTrue(assembler.feed(frame.copyOfRange(0, 1)).isEmpty())
        assertEquals("DH-TwI-TEST00", Protocol.ssid(assembler.feed(frame.copyOfRange(1, frame.size)).single()))
    }
    @Test fun skipsNoiseBeforeAHeader() {
        val frame = hex("AC55000F2544482D5477492D544553543030BF")
        val assembler = FrameAssembler()
        val responses = assembler.feed(byteArrayOf(0x00, 0x11, 0x22) + frame)
        assertEquals("DH-TwI-TEST00", Protocol.ssid(responses.single()))
    }
    @Test fun deliversTwoFramesFromOneChunk() {
        val frame = hex("AC55000F2544482D5477492D544553543030BF")
        assertEquals(2, FrameAssembler().feed(frame + frame).size)
    }
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
