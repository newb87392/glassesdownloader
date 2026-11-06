package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDeadlineWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()

    @Test fun indexUsesWholeReadDeadline() {
        val index = source.substringAfter("private fun textFrom(").substringBefore("private fun open(")
        assertTrue(index.contains("boundedInput(conn, INDEX_TIMEOUT_MS"))
    }

    @Test fun bothStorageTargetsUseWholeFileDeadline() {
        val privateDownload = source.substringAfter("private fun downloadPrivate(").substringBefore("private fun downloadOne(")
        val galleryDownload = source.substringAfter("private fun downloadOne(").substringBefore("private fun looksLikeError(")
        assertTrue(privateDownload.contains("boundedInput(conn, FILE_TIMEOUT_MS"))
        assertTrue(galleryDownload.contains("boundedInput(conn, FILE_TIMEOUT_MS"))
    }
}
