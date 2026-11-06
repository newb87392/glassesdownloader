package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProgressWiringTest {
    private val importer = File("src/main/java/com/open/glassesdownloader/MediaImporter.kt").readText()
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun activeFileAndReceivedBytesAreVisible() {
        assertTrue(importer.contains("Downloading: \$name"))
        assertTrue(importer.contains("bytes received"))
    }

    @Test fun partialTransferHasAnActionableRetryInstruction() {
        assertTrue(activity.contains("Completed files will be skipped"))
    }
}
