package com.open.glassesdownloader

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticTerminalStatusTest {
    @Test fun successIsOnlyReportedWhenBothPhasesSucceed() {
        assertEquals("Diagnostic complete", DiagnosticTerminalStatus.resolve(false, false, false))
        assertEquals("Finished with errors", DiagnosticTerminalStatus.resolve(false, true, false))
        assertEquals("Diagnostic failed", DiagnosticTerminalStatus.resolve(false, false, true))
        assertEquals("Diagnostic failed", DiagnosticTerminalStatus.resolve(false, true, true))
        assertEquals("Diagnostic cancelled", DiagnosticTerminalStatus.resolve(true, false, false))
    }
}
