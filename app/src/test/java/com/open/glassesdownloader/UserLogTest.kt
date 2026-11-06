package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLogTest {
    @Test fun keepsRecentLinesWithinBound() {
        val old = "a".repeat(40) + "\n" + "middle\n"
        val text = UserLog.append(old, "latest", 24)
        assertTrue(text.length <= 24)
        assertTrue(text.endsWith("latest\n"))
        assertTrue(!text.contains("a".repeat(40)))
    }

    @Test fun singleMessageTooLongIsAlsoCapped() {
        assertEquals("defgh\n", UserLog.append("", "abcdefgh", 6))
    }
}
