package com.open.glassesdownloader

import org.json.JSONObject
import java.io.IOException
import java.util.Locale

/** Thrown when the user cancels; distinguished from real failures so the UI can say so. */
class TransferCancelled : IOException("Cancelled")

/**
 * Pure parser for the glasses' JSON media index (`/app/getfilelist`, and the
 * `?custom=1&cmd=3015` variant of the older family). No Android types, so it is
 * covered by plain JVM unit tests.
 */
object MediaIndex {
    /** Media actually supported for download; anything else is reported, not silently dropped. */
    private val mediaExtensions = setOf("jpg", "jpeg", "png", "mp4", "aac", "wav", "m4a")

    /** Local folders the private gallery understands. */
    private val localFolders = setOf("EVENT", "LOOP", "AAC", "EMR")

    private val safeName = Regex("^[A-Za-z0-9_.-]+$")

    /**
     * @param remotePath path as reported by the glasses, folder included when the firmware does that
     * @param localFolder one of EVENT/LOOP/AAC/EMR, so private storage keeps a flat two-level layout
     */
    enum class SizeUnit { BYTES, KIB_FLOOR }

    data class Item(val remotePath: String, val localFolder: String, val localName: String,
                    val timestamp: String, val expectedSize: Long? = null,
                    val sizeUnit: SizeUnit = SizeUnit.BYTES) {
        val relativeName: String get() = "$localFolder/$localName"
    }

    data class Result(val items: List<Item>, val declared: Int, val rejected: List<String>)

    fun parse(text: String, sizeUnit: SizeUnit = SizeUnit.BYTES): Result {
        val root = JSONObject(FileListCleaner.clean(text))
        require(root.optInt("result", -1) == 0) { "Glasses returned result=${root.optInt("result")}" }
        val groups = root.getJSONArray("info")
        val items = mutableListOf<Item>()
        val rejected = mutableListOf<String>()
        var declared = 0
        val seen = mutableSetOf<String>()
        for (i in 0 until groups.length()) {
            val files = groups.getJSONObject(i).optJSONArray("files") ?: continue
            for (j in 0 until files.length()) {
                val obj = files.optJSONObject(j) ?: continue
                declared++
                val raw = obj.optString("name", "")
                val item = item(raw, obj.optString("createtimestr", ""),
                    obj.optLong("size", -1L).takeIf { it > 0L }, sizeUnit)
                if (item == null || !seen.add(item.remotePath)) rejected.add(raw)
                else items.add(item)
            }
        }
        return Result(items, declared, rejected)
    }

    private fun item(rawName: String, timestamp: String, expectedSize: Long?, sizeUnit: SizeUnit): Item? {
        val path = rawName.replace('\\', '/').trim().trimStart('/')
        if (path.isEmpty() || path.contains("..")) return null
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val name = segments.last()
        if (!safeName.matches(name)) return null
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (extension !in mediaExtensions) return null
        return Item(path, localFolder(segments, extension), name, timestamp, expectedSize, sizeUnit)
    }

    /** Keep a folder the gallery already knows; otherwise derive one from the media type. */
    private fun localFolder(segments: List<String>, extension: String): String {
        val reported = segments.dropLast(1).map { it.uppercase(Locale.US) }.lastOrNull { it in localFolders }
        if (reported != null) return reported
        return when (extension) {
            "jpg", "jpeg", "png" -> "EVENT"
            "mp4" -> "LOOP"
            else -> "AAC"
        }
    }
}
