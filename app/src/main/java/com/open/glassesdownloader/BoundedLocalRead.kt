package com.open.glassesdownloader

import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Bounds callers even if a local provider ignores interruption in open() or read(). */
internal object BoundedLocalRead {
    private val number = AtomicInteger()
    // Never grow an unbounded number of stuck provider threads. A saturated pool fails fast.
    private val workers = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        java.util.concurrent.SynchronousQueue(), { task ->
            Thread(task, "media-verification-${number.incrementAndGet()}").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy())

    fun <T> run(timeoutMs: Long, cancelled: () -> Boolean, block: (abandoned: () -> Boolean) -> T): T {
        require(timeoutMs > 0L)
        if (cancelled() || Thread.currentThread().isInterrupted) throw TransferCancelled()
        val abandoned = AtomicBoolean(false)
        val started = System.nanoTime()
        val future = try {
            workers.submit(Callable { block { abandoned.get() || cancelled() || Thread.currentThread().isInterrupted } })
        } catch (_: RejectedExecutionException) {
            throw TransferTimedOut("Phone storage verifier is busy. Close and reopen the app; restart the phone if it persists.")
        }
        try {
            while (true) {
                if (cancelled()) throw TransferCancelled()
                val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000L
                if (remaining <= 0L) throw TransferTimedOut(
                    "Saved media verification timed out after ${timeoutMs / 1000}s. Restart the phone if it persists.")
                try {
                    val result = future.get(minOf(remaining, 200L), TimeUnit.MILLISECONDS)
                    if (cancelled()) throw TransferCancelled()
                    return result
                } catch (_: TimeoutException) { /* Recheck elapsed deadline and cancellation. */ }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw TransferCancelled()
        } catch (e: ExecutionException) {
            throw (e.cause ?: IOException("Local storage verification failed", e))
        } finally {
            abandoned.set(true)
            if (!future.isDone) future.cancel(true)
        }
    }
}
