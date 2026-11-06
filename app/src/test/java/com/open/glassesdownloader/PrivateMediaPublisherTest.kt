package com.open.glassesdownloader

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PrivateMediaPublisherTest {
    @Test fun identicalExistingFileIsKeptWithoutCreatingArchive() {
        val root = Files.createTempDirectory("publish").toFile()
        try {
            val old = File(root, "media.jpg").apply { writeText("original") }
            val part = File(root, "media.jpg.part").apply { writeText("original") }
            val archive = File(root, "archive")
            PrivateMediaPublisher.publish(part, old, archive) { assertEquals("original", old.readText()) }
            assertEquals("original", old.readText())
            assertFalse(part.exists())
            assertFalse(archive.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun differingExistingFileIsRetainedWhenNewOneIsPublished() {
        val root = Files.createTempDirectory("publish").toFile()
        try {
            val old = File(root, "media.jpg").apply { writeText("previous") }
            val part = File(root, "media.jpg.part").apply { writeText("new") }
            val archive = File(root, "archive")
            val kept = PrivateMediaPublisher.publish(part, old, archive) { assertEquals("new", old.readText()) }
            assertEquals("new", old.readText())
            assertEquals("previous", kept!!.readText())
            assertTrue(kept.canonicalPath.startsWith(archive.canonicalPath))
        } finally { root.deleteRecursively() }
    }

    @Test fun publishedEarlierCopyAppearsInPrivateGalleryCatalog() {
        val root = Files.createTempDirectory("publish").toFile()
        try {
            val current = File(root, "glasses-media/EVENT/photo.jpg").apply { parentFile!!.mkdirs(); writeText("earlier") }
            val staged = File(current.parentFile, "photo.jpg.part").apply { writeText("new original") }
            val archiveRoot = File(root, "media-archive")
            val kept = PrivateMediaPublisher.publish(staged, current, File(archiveRoot, "EVENT")) { }
            val entry = PrivateMediaCatalog.all(File(root, "glasses-media"), archiveRoot)
                .firstOrNull { it.second == kept }
            assertNotNull("Preserved phone copy must be visible and exportable", entry)
            assertEquals(kept, PrivateMediaCatalog.file(File(root, "glasses-media"), archiveRoot, entry!!.first))
            assertEquals("earlier", kept!!.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun stalledComparisonLeavesEarlierPhoneCopyInPlace() {
        val root = Files.createTempDirectory("publish").toFile()
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val old = File(root, "media.jpg").apply { writeText("abc") }
            val part = File(root, "media.jpg.part").apply { writeText("xyz") }
            val started = System.nanoTime()
            try {
                PrivateMediaPublisher.publish(part, old, File(root, "archive"),
                    compareTimeoutMs = 80L,
                    readFile = { file ->
                        if (file == old) object : java.io.InputStream() {
                            override fun read(): Int { while (true) try { gate.await(); break } catch (_: InterruptedException) { }; return -1 }
                            override fun read(b: ByteArray, off: Int, len: Int): Int = read()
                        } else file.inputStream()
                    }) { fail("Verification must not be reported") }
                fail("Blocked comparison must time out")
            } catch (_: TransferTimedOut) { }
            assertTrue((System.nanoTime() - started) / 1_000_000L < 2000L)
            assertEquals("abc", old.readText())
            assertEquals("xyz", part.readText())
        } finally { gate.countDown(); root.deleteRecursively() }
    }

    @Test fun failedReceiptRestoresPreviousFile() {
        val root = Files.createTempDirectory("publish").toFile()
        try {
            val old = File(root, "media.jpg").apply { writeText("previous") }
            val part = File(root, "media.jpg.part").apply { writeText("new") }
            try {
                PrivateMediaPublisher.publish(part, old, File(root, "archive")) { throw IOException("receipt failed") }
                fail("Failure should be reported")
            } catch (e: IOException) { assertTrue(e.message.orEmpty().contains("receipt failed")) }
            assertEquals("previous", old.readText())
        } finally { root.deleteRecursively() }
    }
}
