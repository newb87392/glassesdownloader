package com.open.glassesdownloader

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/** App-private, last-operation status for diagnostics when Android logging/UI dumps fail. */
internal class OperationStatusJournal(private val target: File) {
    private companion object {
        val processLock = Any()
        val processIdentity = UUID.randomUUID().toString()
    }
    data class Counts(val found: Int, val downloaded: Int, val skipped: Int, val failed: Int)
    data class Snapshot(
        val sessionId: String,
        val phase: String,
        val status: String,
        val summary: Counts?,
        val recordedAtMillis: Long,
        val startedAtMillis: Long = recordedAtMillis,
        val startedElapsedMillis: Long? = null,
        val processId: String = processIdentity,
    )

    fun write(snapshot: Snapshot) {
        require(snapshot.sessionId.isNotBlank())
        require(snapshot.phase == "running" || snapshot.phase == "terminal")
        val parent = target.parentFile ?: throw IOException("Status file has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create status directory")
        val summary = snapshot.summary?.let {
            JSONObject().put("found", it.found).put("downloaded", it.downloaded)
                .put("skipped", it.skipped).put("failed", it.failed)
        } ?: JSONObject.NULL
        val body = JSONObject()
            .put("schema", 1)
            .put("session_id", snapshot.sessionId)
            .put("phase", snapshot.phase)
            .put("status", snapshot.status)
            .put("summary", summary)
            .put("process_id", snapshot.processId)
            .put("started_at_ms", snapshot.startedAtMillis)
            .put("started_elapsed_ms", snapshot.startedElapsedMillis)
            .put("recorded_at_ms", snapshot.recordedAtMillis)
            .toString().toByteArray(Charsets.UTF_8)
        synchronized(processLock) {
            // The file lock also protects against a second app process; the process lock
            // prevents Java's overlapping-lock exception between recreated activities.
            RandomAccessFile(File(parent, target.name + ".lock"), "rw").channel.use { channel ->
                val lock = channel.lock()
                try {
                    val previous = if (target.isFile) runCatching { JSONObject(target.readText(Charsets.UTF_8)) }.getOrNull() else null
                    if (previous != null) {
                        val priorTime = previous.optLong("recorded_at_ms", -1L)
                        val sameSession = previous.optString("session_id") == snapshot.sessionId
                        val sameProcess = previous.optString("process_id") == snapshot.processId
                        // Android runs this app in one process. A new process has no
                        // surviving callbacks from its predecessor, even if the wall
                        // clock has moved backward since the old snapshot.
                        if (sameProcess && (priorTime > snapshot.recordedAtMillis ||
                            (priorTime == snapshot.recordedAtMillis && !sameSession) ||
                            (sameSession && previous.optString("phase") == "terminal" && snapshot.phase == "running")))
                            return
                    }
                    val temp = File(parent, target.name + ".${UUID.randomUUID()}.part")
                    try {
                        FileOutputStream(temp, false).use { stream ->
                            stream.write(body)
                            stream.fd.sync()
                        }
                        if (!temp.renameTo(target)) throw IOException("Cannot publish status snapshot")
                    } finally {
                        if (temp.exists()) temp.delete()
                    }
                } finally {
                    lock.release()
                }
            }
        }
    }
}
