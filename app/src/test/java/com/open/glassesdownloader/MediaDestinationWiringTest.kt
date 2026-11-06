package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDestinationWiringTest {
    @Test fun checksDestinationsBeforeFirstMediaWrite() {
        val transfer = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()
            .substringAfter("fun transfer(network: Network?): Summary {")
            .substringBefore("private fun loadIndex(")
        val guard = transfer.indexOf("MediaDestinationPolicy.requireUnique(index.items)")
        val write = transfer.indexOf("downloadPrivate(network, endpoint, item)")
        assertTrue("Destination check must precede any download", guard >= 0 && guard < write)
    }
}
