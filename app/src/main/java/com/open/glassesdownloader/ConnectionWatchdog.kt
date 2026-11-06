package com.open.glassesdownloader

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actively disconnects a request even if a peer trickles HTTP headers forever. */
internal class ConnectionWatchdog(durationMs: Long, private val label: String,
                                  disconnect: () -> Unit) : Closeable {
    private val expired = AtomicBoolean(false)
    private val timer = scheduler.schedule({
        expired.set(true)
        try { disconnect() } catch (_: Exception) { }
    }, durationMs, TimeUnit.MILLISECONDS)

    fun check() {
        if (expired.get()) throw IOException("$label timed out. Keep the glasses powered and nearby, then retry.")
    }

    override fun close() { timer.cancel(false) }

    private companion object {
        val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "glasses-http-deadline").apply { isDaemon = true }
        }
    }
}
