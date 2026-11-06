package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyJoinRetryTest {
    @Test fun connectedWinsEvenAtAttemptDeadline() {
        assertEquals(JoinDecision.JOINED, LegacyJoinRetry.decide(12_000L, 0L, 1, true))
    }

    @Test fun waitingWithinAnAttemptDoesNotDisconnectAgain() {
        assertEquals(JoinDecision.WAIT, LegacyJoinRetry.decide(11_999L, 0L, 1, false))
    }

    @Test fun twoExpiredAttemptsRetryButThirdFails() {
        assertEquals(JoinDecision.RETRY, LegacyJoinRetry.decide(12_000L, 0L, 1, false))
        assertEquals(JoinDecision.RETRY, LegacyJoinRetry.decide(24_000L, 12_000L, 2, false))
        assertEquals(JoinDecision.FAIL, LegacyJoinRetry.decide(36_000L, 24_000L, 3, false))
    }
}
