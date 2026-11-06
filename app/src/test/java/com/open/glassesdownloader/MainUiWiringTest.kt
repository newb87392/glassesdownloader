package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source wiring checks; on-device rendering is checked separately. */
class MainUiWiringTest {
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()
    private val ui = activity.substringAfter("private fun buildUi()").substringBefore("private companion object")

    @Test fun uiCandidateHasDistinctVersionFromTestedRollback() {
        val build = File("build.gradle.kts").readText()
        assertTrue(build.contains("versionCode = 31"))
        assertTrue(build.contains("versionName = \"0.13.0-rc17\""))
    }

    @Test fun statusMonitorOccupiesTopThirdAndControlsScrollBelow() {
        assertTrue(ui.contains("root.addView(monitor, LinearLayout.LayoutParams(-1, 0, 1f))"))
        assertTrue(ui.contains("root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 2f))"))
        assertTrue(ui.indexOf("root.addView(monitor,") < ui.indexOf("root.addView(scroll,"))
        assertTrue(ui.contains("logScroll.addView(logView)"))
        assertTrue(ui.contains("monitor.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))"))
    }

    @Test fun privateGalleryShowsExplicitDataLossAndExportWarning() {
        assertTrue(ui.contains("Uninstalling this app or clearing its data deletes downloads in the private gallery."))
        assertTrue(ui.contains("Export files you want to keep first."))
        assertTrue(ui.contains("body.addView(storageWarning"))
        assertFalse(ui.contains("storageWarning.visibility"))
        assertFalse(ui.contains("visibility = if (effective == StorageTarget.PRIVATE)"))
    }

    @Test fun actionButtonsRemainFingerSizedAndCancellationAvailable() {
        assertTrue(activity.contains("minimumHeight = dp(48)"))
        assertTrue(ui.contains("button(\"Connect & download\""))
        assertTrue(ui.contains("button(\"Cancel / disconnect\""))
        assertTrue(ui.contains("button(\"Choose Bluetooth device\""))
    }

    @Test fun newLogLinesAlwaysAutoScrollToBottom() {
        // The top monitor log is a live tail: every appended line has to bring the
        // newest text into view, even if the reader had scrolled up earlier.
        assertFalse(activity.contains("canScrollVertically(1)"))
        assertTrue(activity.contains("logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }"))
    }
}
