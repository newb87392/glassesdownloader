package com.open.glassesdownloader

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native activity wiring for durable status capture, independent of UIAutomator/logcat. */
class OperationStatusWiringTest {
    private val source = File("src/main/java/com/open/glassesdownloader/MainActivity.kt").readText()

    @Test fun startsUniqueSessionBeforeRecordingRunningState() {
        val setBusy = source.substringAfter("private fun setBusy(").substringBefore("private fun finishOperation(")
        assertTrue(setBusy.contains("journalSessionId = UUID.randomUUID().toString()"))
        assertTrue(setBusy.contains("journalStartedAtMillis = journalClock.updateAndGet"))
        assertTrue(setBusy.contains("journalStartedElapsedMillis = SystemClock.elapsedRealtime()"))
        assertTrue(setBusy.contains("persistStatus(if (value) \"running\" else \"terminal\", title)"))
        assertTrue(setBusy.contains("startedAtMillis = journalStartedAtMillis"))
        assertTrue(setBusy.contains("startedElapsedMillis = journalStartedElapsedMillis"))
    }

    @Test fun completeTransferCountsReachJournalBeforeTerminalIsPublished() {
        val transfer = source.substringAfter("private fun performTransfer(").substringBefore("private fun runBleWifiCrossover(")
        assertTrue(transfer.contains("lastTransferSummary = result"))
        assertTrue(transfer.indexOf("lastTransferSummary = result") < transfer.indexOf("finishOperation(if (result.failed > 0)"))
        assertTrue(source.contains("summary = lastTransferSummary?.let"))
    }

    @Test fun diagnosticWriteRunsOffMainThreadAndDoesNotBlockTransfer() {
        assertTrue(source.contains("journalExecutor.execute"))
        assertTrue(source.contains("journal.write(snapshot)"))
        assertTrue(source.contains("journalExecutor.shutdown()"))
        assertTrue(source.contains("catch (_: RejectedExecutionException)"))
        assertTrue(source.contains("journalClock.updateAndGet"))
    }
    @Test fun diagnosticTerminalIncludesTransferAndDiagnosticFailures() {
        assertTrue(source.contains("runBleWifiCrossover(network, token, workerCancellation, result.failed > 0)"))
        assertTrue(source.contains("DiagnosticTerminalStatus.resolve(workerCancellation.get(), transferFailed, diagnosticFailed)"))
        val probes = source.substringAfter("private fun runBleWifiCrossover(").substringBefore("private fun stop()")
        assertTrue(probes.contains("if (code != 200 || error.isNotEmpty()) diagnosticFailed = true"))
    }
    @Test fun bluetoothPreflightFailureStillStartsJournaledOperation() {
        val preflight = source.substringAfter("private fun startBleAfterPermission()").substringBefore("private fun launchBleSession()")
        assertTrue(preflight.indexOf("setBusy(true, \"Checking Bluetooth\")") < preflight.indexOf("bt.adapter?.isEnabled"))
        assertTrue(preflight.contains("setBusy(true, \"Checking Bluetooth\")"))
    }
    @Test fun activityTeardownRecordsInterruptedTerminalBeforeClosingJournal() {
        val teardown = source.substringAfter("override fun onDestroy()")
        assertTrue(teardown.contains("if (busy) persistStatus(\"terminal\", \"Interrupted — reopen the app and retry\")"))
        assertTrue(teardown.indexOf("persistStatus(\"terminal\", \"Interrupted") < teardown.indexOf("journalExecutor.shutdown()"))
    }
}
