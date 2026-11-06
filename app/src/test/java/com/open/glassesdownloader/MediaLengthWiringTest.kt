package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLengthWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()

    @Test fun bothTargetsRequireRemoteCompletenessBeforeReceipt() {
        val privatePart = source.substringAfter("private fun downloadPrivate(").substringBefore("private fun downloadOne(")
        val sharedPart = source.substringAfter("private fun downloadOne(").substringBefore("private fun looksLikeError(")
        val call = "MediaLengthPolicy.requireComplete(item.expectedSize, conn.contentLengthLong, written, item.sizeUnit)"
        assertTrue(source.contains("MediaIndex.parse(body, endpoint.sizeUnit)"))
        assertTrue(privatePart.contains(call))
        assertTrue(sharedPart.contains(call))
        assertTrue(privatePart.indexOf(call) < privatePart.indexOf("receipts.record("))
        assertTrue(sharedPart.indexOf(call) < sharedPart.indexOf("receipts.record("))
    }
}
