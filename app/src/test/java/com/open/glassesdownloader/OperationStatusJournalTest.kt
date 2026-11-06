package com.open.glassesdownloader

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OperationStatusJournalTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun terminalStatusAndWholeLibraryCountsReplaceTheRunningState() {
        val file = File(temp.root, "diagnostics/last-operation.json")
        val journal = OperationStatusJournal(file)
        journal.write(OperationStatusJournal.Snapshot("session-1", "running", "Downloading…", null, 100L, startedAtMillis = 100L))
        val running = JSONObject(file.readText())
        assertEquals("running", running.getString("phase"))
        assertEquals("session-1", running.getString("session_id"))
        assertTrue(running.isNull("summary"))

        journal.write(OperationStatusJournal.Snapshot("session-1", "terminal", "Transfer complete",
            OperationStatusJournal.Counts(16, 2, 14, 0), 200L, startedAtMillis = 100L,
            startedElapsedMillis = 10L))
        val done = JSONObject(file.readText())
        assertEquals("terminal", done.getString("phase"))
        assertEquals("Transfer complete", done.getString("status"))
        assertEquals("session-1", done.getString("session_id"))
        assertEquals(16, done.getJSONObject("summary").getInt("found"))
        assertEquals(2, done.getJSONObject("summary").getInt("downloaded"))
        assertEquals(14, done.getJSONObject("summary").getInt("skipped"))
        assertEquals(0, done.getJSONObject("summary").getInt("failed"))
        assertEquals(200L, done.getLong("recorded_at_ms"))
        assertEquals(100L, done.getLong("started_at_ms"))
        assertEquals(10L, done.getLong("started_elapsed_ms"))
        assertFalse(File(file.parentFile, file.name + ".part").exists())
    }

    @Test fun nextSessionGetsDifferentIdentitySoOldTerminalCannotBeMistakenForNew() {
        val file = File(temp.root, "last-operation.json")
        val journal = OperationStatusJournal(file)
        journal.write(OperationStatusJournal.Snapshot("first", "terminal", "Transfer complete", null, 1L))
        journal.write(OperationStatusJournal.Snapshot("second", "running", "Finding glasses", null, 2L))
        val current = JSONObject(file.readText())
        assertEquals("second", current.getString("session_id"))
        assertEquals("running", current.getString("phase"))
    }

    @Test fun delayedOlderActivityCannotOverwriteNewerTerminal() {
        val file = File(temp.root, "last-operation.json")
        val oldActivity = OperationStatusJournal(file)
        val newActivity = OperationStatusJournal(file)
        newActivity.write(OperationStatusJournal.Snapshot("new", "terminal", "Transfer complete",
            OperationStatusJournal.Counts(2, 2, 0, 0), 200L))
        oldActivity.write(OperationStatusJournal.Snapshot("old", "running", "Finding glasses", null, 100L))
        val current = JSONObject(file.readText())
        assertEquals("new", current.getString("session_id"))
        assertEquals("terminal", current.getString("phase"))
    }

    @Test fun aNewProcessCanJournalDespiteDeviceClockRollback() {
        val file = File(temp.root, "last-operation.json")
        val journal = OperationStatusJournal(file)
        journal.write(OperationStatusJournal.Snapshot("before-restart", "terminal", "Cancelled", null,
            500L, processId = "old-process"))
        journal.write(OperationStatusJournal.Snapshot("after-restart", "running", "Finding glasses", null,
            100L, processId = "new-process"))
        val current = JSONObject(file.readText())
        assertEquals("after-restart", current.getString("session_id"))
        assertEquals("new-process", current.getString("process_id"))
    }

    @Test fun overlappingWritersLeaveParseableNewestSnapshot() {
        val file = File(temp.root, "last-operation.json")
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val threads = (1..6).map { worker ->
            Thread {
                try {
                    start.await()
                    val journal = OperationStatusJournal(file)
                    for (i in 1..20) journal.write(OperationStatusJournal.Snapshot(
                        "session-$worker-$i", "terminal", "Cancelled", null, (worker * 100 + i).toLong()))
                } catch (t: Throwable) { errors.add(t) }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join(5000) }
        assertTrue(threads.none { it.isAlive })
        assertTrue(errors.toString(), errors.isEmpty())
        val current = JSONObject(file.readText())
        assertEquals(620L, current.getLong("recorded_at_ms"))
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.endsWith(".part") })
    }
}
