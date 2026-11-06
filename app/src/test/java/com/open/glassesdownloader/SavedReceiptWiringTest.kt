package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedReceiptWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()

    @Test fun preexistingPrivateAndGalleryMediaRequireVerifiedReceipts() {
        val check = source.substringAfter("private fun alreadySaved(").substringBefore("private fun downloadPrivate(")
        assertTrue(check.contains("receipts.matches(item, file.length())"))
        assertTrue(check.contains("receipts.matches(item, size)"))
    }

    @Test fun explainsOneTimeRedownloadOfPreviouslyUnverifiedFiles() {
        assertTrue(source.contains("Files saved before verification receipts may be downloaded again"))
    }

    @Test fun completedPrivateAndGalleryTransfersWriteReceipts() {
        val privatePart = source.substringAfter("private fun downloadPrivate(").substringBefore("private fun downloadOne(")
        val sharedPart = source.substringAfter("private fun downloadOne(").substringBefore("private fun looksLikeError(")
        assertTrue(privatePart.contains("receipts.record(item, finalFile.length())"))
        assertTrue(sharedPart.contains("receipts.record(item, written)"))
    }
}
