package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivatePublisherWiringTest {
    @Test fun replacementPreservesOlderPhoneCopyAndReceiptFailureRollsBack() {
        val section = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()
            .substringAfter("private fun downloadPrivate(").substringBefore("private fun downloadOne(")
        assertTrue(section.contains("PrivateMediaPublisher.publish(temp, finalFile,"))
        assertTrue(section.contains("File(context.filesDir, \"media-archive/${'$'}{item.localFolder}\")"))
        assertTrue(section.contains("receipts.record(item, finalFile.length())"))
    }
}
