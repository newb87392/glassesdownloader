package com.open.glassesdownloader

/** A diagnostic success must not hide a failed transfer or a probe exception. */
internal object DiagnosticTerminalStatus {
    fun resolve(cancelled: Boolean, transferFailed: Boolean, diagnosticFailed: Boolean): String = when {
        cancelled -> "Diagnostic cancelled"
        diagnosticFailed -> "Diagnostic failed"
        transferFailed -> "Finished with errors"
        else -> "Diagnostic complete"
    }
}
