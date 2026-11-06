package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCancellationWiringTest {
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun oldRetryCannotRestartAfterCancelAndNewStart() {
        val finish = activity.substringAfter("private fun finishOperation(title: String)").substringBefore("private fun fail(")
        assertTrue(finish.contains("cancelRecoveryTask()"))
        val destroy = activity.substringAfter("override fun onDestroy()")
        assertTrue(destroy.contains("cancelRecoveryTask()"))
    }
}
