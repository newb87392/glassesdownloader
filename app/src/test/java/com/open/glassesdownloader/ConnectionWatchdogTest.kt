package com.open.glassesdownloader

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ConnectionWatchdogTest {
    @Test fun disconnectsEvenIfHeadersContinueToTrickle() {
        val disconnected = CountDownLatch(1)
        ConnectionWatchdog(30L, "Media index") { disconnected.countDown() }.use { watchdog ->
            assertTrue("Timed request must actively disconnect", disconnected.await(1, TimeUnit.SECONDS))
            try {
                watchdog.check()
                fail("Timed-out response must not be accepted")
            } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("Media index")) }
        }
    }

    @Test fun normalCompletionCancelsPendingDisconnect() {
        val disconnected = CountDownLatch(1)
        ConnectionWatchdog(100L, "Media file") { disconnected.countDown() }.use { watchdog ->
            watchdog.check()
        }
        assertFalse(disconnected.await(150, TimeUnit.MILLISECONDS))
    }
}
