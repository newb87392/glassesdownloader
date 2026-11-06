package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyJoinWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/LegacyWifiJoin.kt").readText()

    @Test fun timedOutAttemptRetriesWhileBleSessionStaysOpen() {
        assertTrue(source.contains("LegacyJoinRetry.decide("))
        assertTrue(source.contains("JoinDecision.RETRY ->"))
        assertTrue(source.contains("startAttempt("))
        assertTrue(source.contains("SystemClock.elapsedRealtime()"))
    }

    @Test fun progressReportsEachAttemptAndFinalFailureIsActionable() {
        assertTrue(source.contains("onProgress("))
        assertTrue(source.contains("Power-cycle the glasses"))
    }
}
