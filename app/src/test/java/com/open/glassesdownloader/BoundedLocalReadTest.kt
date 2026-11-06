package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BoundedLocalReadTest {
    @Test fun saturatedVerifierFailsFastWithoutCreatingUnboundedWorkers() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val callers = (1..2).map {
            Thread {
                BoundedLocalRead.run(3000L, { false }) { _ ->
                    entered.countDown()
                    release.await()
                    1
                }
            }.apply { start() }
        }
        try {
            assertTrue(entered.await(2L, TimeUnit.SECONDS))
            val started = System.nanoTime()
            try {
                BoundedLocalRead.run(500L, { false }) { _ -> 1 }
                fail("Saturated verifier accepted a third blocked task")
            } catch (e: TransferTimedOut) {
                assertTrue(e.message.orEmpty().contains("busy"))
            }
            assertTrue((System.nanoTime() - started) / 1_000_000L < 1000L)
        } finally {
            release.countDown()
            callers.forEach { it.join(2000L) }
        }
    }
}
