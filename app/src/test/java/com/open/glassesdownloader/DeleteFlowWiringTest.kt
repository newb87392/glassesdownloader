package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deletion is the only destructive feature: pin its gates and its single call site. */
class DeleteFlowWiringTest {
    private val main = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()
    private val endpoint = File("src/main/java/com/open/glassesdownloader/Endpoint.kt").readText()

    @Test fun deleteUrlIsBuiltInOnePlaceForBothFirmwareFamilies() {
        assertTrue(endpoint.contains("fun deleteUrl("))
        assertTrue(endpoint.contains("/app/deletefile?file="))
        assertTrue(endpoint.contains("cmd=4003"))
        // The activity must never hand-write the endpoint.
        assertTrue(!main.contains("/app/deletefile"))
    }

    @Test fun userChoosesFilesOrEverythingAndBothAskForConfirmation() {
        assertTrue(main.contains("CHOICE_MODE_MULTIPLE"))
        assertTrue(main.contains("\"Delete selected\""))
        assertTrue(main.contains("\"Delete ALL\""))
        assertTrue(main.contains("\"Delete from the glasses?\""))
        assertTrue(main.contains("This cannot be undone."))
        // Confirmation must come before the deleting code exists in the source flow.
        assertTrue(main.indexOf("private fun confirmDelete(") < main.indexOf("private fun runDelete("))
    }

    @Test fun aDeleteIsJudgedByAFreshListingNotByReplies() {
        // The device sometimes drops a delete reply while busy; the index must decide.
        assertTrue(main.contains("val remaining = fetchGlassesIndex(null)"))
        assertTrue(main.contains("Still on the glasses:"))
        assertTrue(main.contains("none of them are listed any more"))
    }

    @Test fun deletionNeverRunsWhileAnotherOperationIsBusy() {
        assertTrue(main.contains("private fun runDelete("))
        assertTrue(main.contains("if (busy) return"))
        assertEquals(1, Regex("deleteUrl\\(").findAll(main).count())
    }
}
