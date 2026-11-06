package com.open.glassesdownloader

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Never replace a pre-existing private-gallery file without preserving its bytes. */
internal object PrivateMediaPublisher {
    fun publish(staged: File, destination: File, archiveFolder: File,
                cancelled: () -> Boolean = { false },
                compareTimeoutMs: Long = 120_000L,
                readFile: (File) -> InputStream = { it.inputStream() },
                verify: () -> Unit): File? {
        fun check() { if (cancelled() || Thread.currentThread().isInterrupted) throw TransferCancelled() }
        check()
        if (!destination.exists()) {
            if (!staged.renameTo(destination)) throw IOException("Unable to finish private download")
            verify()
            return null
        }
        if (BoundedLocalRead.run(compareTimeoutMs, cancelled) { stop ->
                identical(staged, destination, stop, readFile)
            }) {
            check()
            verify()
            if (!staged.delete()) throw IOException("Unable to clean up verified temporary file")
            return null
        }
        if (!archiveFolder.isDirectory && !archiveFolder.mkdirs())
            throw IOException("Unable to preserve prior phone copy; new download was not published")
        val archive = File.createTempFile(destination.nameWithoutExtension + ".previous-", ".${destination.extension}", archiveFolder)
        if (!archive.delete() || !destination.renameTo(archive))
            throw IOException("Unable to preserve prior phone copy; new download was not published")
        try {
            check()
            if (!staged.renameTo(destination)) throw IOException("Unable to finish private download")
            verify()
            return archive
        } catch (failure: Exception) {
            try {
                if (destination.exists() && !destination.delete())
                    throw IOException("New copy could not be removed; prior copy is in ${archive.absolutePath}")
                if (!archive.renameTo(destination))
                    throw IOException("Prior copy could not be restored; it is in ${archive.absolutePath}")
            } catch (restore: Exception) { failure.addSuppressed(restore) }
            throw failure
        }
    }

    private fun identical(a: File, b: File, cancelled: () -> Boolean,
                          readFile: (File) -> InputStream): Boolean {
        if (a.length() != b.length()) return false
        readFile(a).use { left ->
            readFile(b).use { right ->
                val x = ByteArray(64 * 1024)
                val y = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled() || Thread.currentThread().isInterrupted) throw TransferCancelled()
                    val n = left.read(x)
                    if (n < 0) return true
                    var offset = 0
                    while (offset < n) {
                        val m = right.read(y, offset, n - offset)
                        if (m <= 0) return false
                        offset += m
                    }
                    for (i in 0 until n) if (x[i] != y[i]) return false
                }
            }
        }
    }
}
