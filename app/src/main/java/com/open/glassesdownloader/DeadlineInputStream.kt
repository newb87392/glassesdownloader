package com.open.glassesdownloader

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

internal class TransferTimedOut(message: String) : IOException(message)

/** Bounds an HTTP body by elapsed wall time, not just socket-idle time. */
internal class DeadlineInputStream(
    source: InputStream,
    private val durationMs: Long,
    private val label: String,
    private val elapsedRealtime: () -> Long,
    private val cancelled: () -> Boolean
) : FilterInputStream(source) {
    private val startedAt = elapsedRealtime()

    private fun checkDeadline() {
        if (cancelled() || Thread.currentThread().isInterrupted) throw TransferCancelled()
        if (elapsedRealtime() - startedAt >= durationMs)
            throw TransferTimedOut("$label timed out after ${durationMs / 1000}s. Keep the glasses powered and nearby, then retry.")
    }

    override fun read(): Int {
        checkDeadline()
        val result = super.read()
        checkDeadline()
        return result
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkDeadline()
        val result = super.read(buffer, offset, length)
        checkDeadline()
        return result
    }
}