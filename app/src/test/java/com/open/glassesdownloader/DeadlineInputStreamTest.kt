package com.open.glassesdownloader

import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class DeadlineInputStreamTest {
    @Test fun continuousTrickleStillStopsAtWholeRequestDeadline() {
        var clock = 0L
        val trickle = object : InputStream() {
            override fun read(): Int { clock += 10; return 65 }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                clock += 10
                b[off] = 65
                return 1
            }
        }
        val input = DeadlineInputStream(trickle, 25L, "media index", { clock }, { false })
        val buffer = ByteArray(8)
        assertEquals(1, input.read(buffer))
        assertEquals(1, input.read(buffer))
        try {
            input.read(buffer)
            fail("A trickle should not keep a request alive indefinitely")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("media index"))
            assertTrue(e.message.orEmpty().contains("timed out"))
        }
    }

    @Test fun cancellationStopsEvenWhenDataIsAvailable() {
        val input = DeadlineInputStream(byteArrayOf(1, 2).inputStream(), 1000L,
            "media file", { 0L }, { true })
        try {
            input.read()
            fail("Cancelled transfer must not consume bytes")
        } catch (_: TransferCancelled) { }
    }

    @Test fun cleanEofBeforeDeadlineIsNotAnError() {
        val input = DeadlineInputStream(byteArrayOf(1).inputStream(), 10L,
            "media file", { 5L }, { false })
        assertEquals(1, input.read())
        assertEquals(-1, input.read())
    }
}
