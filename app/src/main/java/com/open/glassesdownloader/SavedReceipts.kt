package com.open.glassesdownloader

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Only a file we wrote completely, with matching path and current bytes, may be skipped. */
internal class SavedReceipts(
    private val root: File,
    private val cancelled: () -> Boolean = { false },
    private val elapsedRealtime: () -> Long = { System.nanoTime() / 1_000_000L },
    private val hashTimeoutMs: Long = 120_000L,
    private val receiptReader: (File) -> List<String> = { it.readLines(Charsets.US_ASCII) }
) {
    private fun location(item: MediaIndex.Item) = File(root, "${item.localFolder}/${item.localName}.receipt")
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun remoteId(item: MediaIndex.Item): String = hex(
        MessageDigest.getInstance("SHA-256").digest(
            "${item.remotePath}\n${item.timestamp}\n${item.expectedSize ?: ""}\n${item.sizeUnit}".toByteArray(Charsets.UTF_8)
        )
    )

    private fun digest(open: () -> InputStream): Pair<Long, String> =
        BoundedLocalRead.run(hashTimeoutMs, cancelled) { stop ->
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            DeadlineInputStream(open(), hashTimeoutMs, "Saved media verification",
                elapsedRealtime, stop).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) { hash.update(buffer, 0, n); count += n }
                }
            }
            count to hex(hash.digest())
        }

    fun matches(item: MediaIndex.Item, size: Long, open: () -> InputStream): Boolean {
        if (size <= 0L || !MediaLengthPolicy.indexMatches(item.expectedSize, item.sizeUnit, size)) return false
        val receipt = location(item)
        if (!receipt.isFile || receipt.length() !in 1L..256L) return false
        return try {
            val lines = BoundedLocalRead.run(hashTimeoutMs, cancelled) { _ -> receiptReader(receipt) }
            lines.size == 4 && lines[0] == "v1" && lines[1] == remoteId(item) &&
                lines[2] == size.toString() && digest(open) == (size to lines[3])
        } catch (e: TransferCancelled) { throw e }
          catch (e: TransferTimedOut) { throw e }
          catch (_: IOException) { false }
    }

    fun record(item: MediaIndex.Item, size: Long, open: () -> InputStream) {
        if (size <= 0L) throw IOException("Cannot record empty media as complete")
        val (actualSize, hash) = digest(open)
        if (actualSize != size) throw IOException("Saved media changed before completion was verified")
        val receipt = location(item)
        if (!receipt.parentFile!!.isDirectory && !receipt.parentFile!!.mkdirs())
            throw IOException("Unable to create media verification directory")
        val temp = File(receipt.parentFile, receipt.name + ".part")
        try {
            FileOutputStream(temp, false).use { output ->
                output.write("v1\n${remoteId(item)}\n$size\n$hash\n".toByteArray(Charsets.US_ASCII))
                output.fd.sync()
            }
            if (!temp.renameTo(receipt)) throw IOException("Unable to save media verification receipt")
        } finally { if (temp.exists()) temp.delete() }
    }
}
