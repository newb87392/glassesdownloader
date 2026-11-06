package com.open.glassesdownloader

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class MediaDestinationPolicyTest {
    @Test fun distinctRemoteFilesCannotOverwriteTheSameLocalDestination() {
        val first = MediaIndex.Item("CAM_A/2026.jpg", "EVENT", "2026.jpg", "")
        val second = MediaIndex.Item("CAM_B/2026.jpg", "EVENT", "2026.jpg", "")
        try {
            MediaDestinationPolicy.requireUnique(listOf(first, second))
            fail("Two remote originals would share one destination")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("2026.jpg"))
            assertTrue(e.message.orEmpty().contains("collision"))
        }
    }

    @Test fun caseOnlyDifferenceAlsoConflictsForSharedStorage() {
        val first = MediaIndex.Item("A/Pic.JPG", "EVENT", "Pic.JPG", "")
        val second = MediaIndex.Item("B/pic.jpg", "EVENT", "pic.jpg", "")
        try {
            MediaDestinationPolicy.requireUnique(listOf(first, second))
            fail("Case-only names should not be treated as unique")
        } catch (_: IOException) { }
    }

    @Test fun sameNameInDifferentFoldersCanCoexist() {
        val first = MediaIndex.Item("EVENT/2026.jpg", "EVENT", "2026.jpg", "")
        val second = MediaIndex.Item("EMR/2026.jpg", "EMR", "2026.jpg", "")
        MediaDestinationPolicy.requireUnique(listOf(first, second))
    }
}
