package com.open.glassesdownloader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SavedReceiptsTest {
    private val item = MediaIndex.Item("EVENT/photo.jpg", "EVENT", "photo.jpg", "")

    @Test fun preexistingNonemptyFileWithoutReceiptIsNotSilentlySkipped() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"))
            assertFalse(receipts.matches(item, media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }

    @Test fun receiptRejectsSameSizeCorruptionAndDifferentRemoteIdentity() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"))
            receipts.record(item, media.length()) { media.inputStream() }
            assertTrue(receipts.matches(item, media.length()) { media.inputStream() })
            media.writeBytes(byteArrayOf(9, 2, 3))
            assertFalse(receipts.matches(item, media.length()) { media.inputStream() })
            media.writeBytes(byteArrayOf(1, 2, 3))
            val alternate = item.copy(remotePath = "OTHER/photo.jpg")
            assertFalse(receipts.matches(alternate, media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }

    @Test fun roundedKibIndexDoesNotRejectVerifiedByteExactFile() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(ByteArray(255028) { 7 }) }
            val itemWithRoundedSize = item.copy(expectedSize = 249L, sizeUnit = MediaIndex.SizeUnit.KIB_FLOOR)
            val receipts = SavedReceipts(File(root, "receipts"))
            receipts.record(itemWithRoundedSize, media.length()) { media.inputStream() }
            assertTrue(receipts.matches(itemWithRoundedSize, media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }

    @Test fun newerRemoteSizeInvalidatesOldReceipt() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"))
            receipts.record(item, media.length()) { media.inputStream() }
            assertFalse(receipts.matches(item.copy(expectedSize = 4L), media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }

    @Test fun changedRemoteTimestampInvalidatesOldReceiptEvenAtSameSize() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"))
            receipts.record(item, media.length()) { media.inputStream() }
            assertFalse(receipts.matches(item.copy(timestamp = "20260102000000"), media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }

    @Test fun cancellationDuringLocalHashIsNotMistakenForAnUnverifiedFile() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            var stop = false
            val receipts = SavedReceipts(File(root, "receipts"), cancelled = { stop })
            receipts.record(item, media.length()) { media.inputStream() }
            stop = true
            try {
                receipts.matches(item, media.length()) { media.inputStream() }
                fail("Cancellation must stop the transfer, not trigger a new download")
            } catch (_: TransferCancelled) { }
        } finally { root.deleteRecursively() }
    }

    @Test fun slowLocalHashHasAnElapsedDeadline() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            var clock = 0L
            val receipts = SavedReceipts(File(root, "receipts"), elapsedRealtime = { clock }, hashTimeoutMs = 25L)
            val slow = {
                object : java.io.InputStream() {
                    override fun read(): Int { clock += 10; return 65 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        clock += 10; b[off] = 65; return 1
                    }
                }
            }
            try {
                receipts.record(item, 100L, slow)
                fail("Trickling local storage should eventually time out")
            } catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("timed out")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun timedOutLocalVerificationIsReportedRatherThanRedownloaded() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            var clock = 0L
            val receipts = SavedReceipts(File(root, "receipts"), elapsedRealtime = { clock }, hashTimeoutMs = 25L)
            receipts.record(item, media.length()) { media.inputStream() }
            val slow = {
                object : java.io.InputStream() {
                    override fun read(): Int { clock += 10; return 65 }
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        clock += 10; b[off] = 65; return 1
                    }
                }
            }
            try {
                receipts.matches(item, media.length(), slow)
                fail("A storage timeout must not silently trigger another transfer")
            } catch (e: java.io.IOException) { assertTrue(e.message.orEmpty().contains("timed out")) }
        } finally { root.deleteRecursively() }
    }

    @Test fun blockedOpenDoesNotTrapVerificationWorker() {
        val root = Files.createTempDirectory("receipts").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"), hashTimeoutMs = 80L)
            receipts.record(item, media.length()) { media.inputStream() }
            val started = System.nanoTime()
            try {
                receipts.matches(item, media.length()) {
                    while (true) try { gate.await(); break } catch (_: InterruptedException) { }
                    media.inputStream()
                }
                fail("Blocked provider open must time out")
            } catch (_: TransferTimedOut) { }
            assertTrue("Caller remained trapped in open", (System.nanoTime() - started) / 1_000_000L < 2000L)
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun blockedReadCannotWriteAReceiptAfterTimeout() {
        val root = Files.createTempDirectory("receipts").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val receipts = SavedReceipts(File(root, "receipts"), hashTimeoutMs = 80L)
            val started = System.nanoTime()
            try {
                receipts.record(item, 3L) {
                    object : java.io.InputStream() {
                        override fun read(): Int { while (true) try { gate.await(); break } catch (_: InterruptedException) { }; return -1 }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = read()
                    }
                }
                fail("Blocked storage read must time out")
            } catch (_: TransferTimedOut) { }
            assertTrue("Caller remained trapped in read", (System.nanoTime() - started) / 1_000_000L < 2000L)
            assertFalse(File(root, "receipts/EVENT/photo.jpg.receipt").exists())
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun blockedReceiptReadReturnsTimeoutInsteadOfHanging() {
        val root = Files.createTempDirectory("receipts").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            SavedReceipts(File(root, "receipts")).record(item, media.length()) { media.inputStream() }
            val receipts = SavedReceipts(File(root, "receipts"), hashTimeoutMs = 80L,
                receiptReader = { file ->
                    while (true) try { gate.await(); break } catch (_: InterruptedException) { }
                    file.readLines(Charsets.US_ASCII)
                })
            val started = System.nanoTime()
            try { receipts.matches(item, media.length()) { media.inputStream() }; fail("Blocked receipt read must time out") }
            catch (_: TransferTimedOut) { }
            assertTrue((System.nanoTime() - started) / 1_000_000L < 2000L)
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun interruptedReceiptCannotMarkFileAsComplete() {
        val root = Files.createTempDirectory("receipts").toFile()
        try {
            val media = File(root, "photo.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val receipts = SavedReceipts(File(root, "receipts"))
            receipts.record(item, media.length()) { media.inputStream() }
            media.writeBytes(byteArrayOf(1, 2))
            assertFalse(receipts.matches(item, media.length()) { media.inputStream() })
        } finally { root.deleteRecursively() }
    }
}
