package com.open.glassesdownloader

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrivateMediaCatalogTest {
    @Test fun archivedCopyIsListedAndCanBeResolvedForExport() {
        val dir = Files.createTempDirectory("media-catalog").toFile()
        try {
            val current = File(dir, "glasses-media/EVENT/photo.jpg").apply { parentFile!!.mkdirs(); writeText("new") }
            val earlier = File(dir, "media-archive/EVENT/photo.bak-123-0.jpg").apply { parentFile!!.mkdirs(); writeText("old") }
            val records = PrivateMediaCatalog.all(File(dir, "glasses-media"), File(dir, "media-archive"))
            assertTrue(records.contains("EVENT/photo.jpg" to current))
            assertTrue(records.contains("ARCHIVED/EVENT/photo.bak-123-0.jpg" to earlier))
            assertEquals(earlier, PrivateMediaCatalog.file(File(dir, "glasses-media"), File(dir, "media-archive"), records.first { it.second == earlier }.first))
        } finally { dir.deleteRecursively() }
    }

    @Test fun rejectsTraversalAndPartialTemporaryCopies() {
        val dir = Files.createTempDirectory("media-catalog").toFile()
        try {
            val root = File(dir, "glasses-media")
            val archive = File(dir, "media-archive")
            val partial = File(archive, "EVENT/unfinished.part").apply { parentFile!!.mkdirs(); writeText("half") }
            assertFalse(PrivateMediaCatalog.all(root, archive).any { it.second == partial })
            listOf("ARCHIVED/../x.jpg", "ARCHIVED/EVENT/../x.jpg", "ARCHIVED/EVENT//x.jpg", "ARCHIVED/EVENT/x.part", "/EVENT/x.jpg").forEach { name ->
                try { PrivateMediaCatalog.file(root, archive, name); fail("accepted $name") }
                catch (_: IllegalArgumentException) { }
            }
        } finally { dir.deleteRecursively() }
    }
}
