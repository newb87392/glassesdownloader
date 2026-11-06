package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpWatchdogWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()

    @Test fun headerReadStartsAfterWatchdogIsArmed() {
        val section = source.substringAfter("private fun open(").substringBefore("private data class Destination")
        val watchdog = section.indexOf("ConnectionWatchdog(durationMs, label) { conn.disconnect() }")
        val headers = section.indexOf("conn.responseCode")
        assertTrue("Header read must not precede watchdog", watchdog >= 0 && watchdog < headers)
        assertTrue(section.contains("watchdog.check()"))
    }

    @Test fun everyRequestCancelsItsWatchdogOnClose() {
        assertTrue(source.contains("private fun close(conn: HttpURLConnection)"))
        val closings = listOf("private fun textFrom(", "private fun downloadPrivate(", "private fun downloadOne(")
        for (i in closings.indices) {
            val section = source.substringAfter(closings[i])
                .substringBefore(if (i + 1 < closings.size) closings[i + 1] else "private fun looksLikeError(")
            assertTrue(section.contains("close(conn)"))
        }
    }
}
