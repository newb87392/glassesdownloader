package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationOwnershipWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun workerOwnsIndependentCancellationAndOnlyFinishesItsOwnOperation() {
        assertTrue(source.contains("val workerCancellation = cancelled"))
        assertTrue(source.contains("val token = operationToken"))
        assertTrue(source.contains("MediaImporter(this, { append(it) }, workerCancellation, target)"))
        assertTrue(source.contains("if (operations.isCurrent(token)) finishOperation("))
        assertTrue(source.contains("cancelled = AtomicBoolean(false)"))
    }

    @Test fun queuedWifiCallbackCannotStartSecondTransfer() {
        assertTrue(source.contains("operations.isCurrent(token) && cycle == wifiCycle && !transferStarted"))
    }
}
