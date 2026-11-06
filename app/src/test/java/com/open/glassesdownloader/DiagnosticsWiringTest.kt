package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsWiringTest {
    private val activity = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun activityBoundsVisibleHistoryAndReportsTerminalStateToLogcat() {
        assertTrue(activity.contains("UserLog.append(logView.text.toString(), line)"))
        assertTrue(activity.contains("Log.i(\"GlassesDownloader\", line)"))
        assertTrue(activity.contains("Log.i(\"GlassesDownloader\", \"STATE: \$title\")"))
    }
}
