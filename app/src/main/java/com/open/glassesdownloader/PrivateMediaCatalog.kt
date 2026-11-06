package com.open.glassesdownloader

import java.io.File

/** Keeps earlier phone copies discoverable, viewable and exportable without exposing other private files. */
internal object PrivateMediaCatalog {
    private val folders = listOf("EVENT", "LOOP", "AAC", "EMR")
    private val filePattern = Regex("^[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|mp4|aac|wav|m4a)$", RegexOption.IGNORE_CASE)

    fun file(currentRoot: File, archiveRoot: File, relativeName: String): File {
        val parts = relativeName.split('/')
        val archived = parts.size == 3 && parts[0] == "ARCHIVED"
        val folder = if (archived) parts.getOrNull(1) else parts.getOrNull(0)
        val name = if (archived) parts.getOrNull(2) else parts.getOrNull(1)
        require(parts.size == (if (archived) 3 else 2) &&
            folder in folders && name != null && filePattern.matches(name) &&
            name != "." && name != "..") { "Unsafe media filename" }
        return File(File(if (archived) archiveRoot else currentRoot, folder!!), name!!)
    }

    fun all(currentRoot: File, archiveRoot: File): List<Pair<String, File>> =
        listOf(currentRoot to "", archiveRoot to "ARCHIVED/").flatMap { (base, prefix) ->
            folders.flatMap { folder ->
                File(base, folder).listFiles()?.asSequence()
                    ?.filter { it.isFile && filePattern.matches(it.name) && it.length() > 0 }
                    ?.map { "$prefix$folder/${it.name}" to it }
                    ?.toList() ?: emptyList()
            }
        }.sortedByDescending { it.second.lastModified() }
}
