package com.open.glassesdownloader

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import java.io.IOException
import java.io.FileOutputStream
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.IdentityHashMap
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Local HTTP media import. No cloud, delete API, credentials storage, or third-party libraries. */
class MediaImporter(
    private val context: Context,
    private val log: (String) -> Unit,
    private val cancelled: AtomicBoolean,
    private val storageTarget: StorageTarget
) {
    data class Summary(val found: Int, val downloaded: Int, val skipped: Int, val failed: Int)

    private companion object {
        const val INDEX_TIMEOUT_MS = 45_000L
        const val FILE_TIMEOUT_MS = 600_000L
    }

    private fun boundedInput(conn: HttpURLConnection, durationMs: Long, label: String): DeadlineInputStream =
        DeadlineInputStream(conn.inputStream, durationMs, label,
            SystemClock::elapsedRealtime, { cancelled.get() })

    /**
     * The two firmware families expose different hosts *and* different APIs. They are not
     * interchangeable; see [Endpoint].
     */
    private val endpoints = Endpoint.ALL

    private val resolver = context.contentResolver
    private val watchdogs = IdentityHashMap<HttpURLConnection, ConnectionWatchdog>()
    private val receipts = SavedReceipts(File(context.filesDir, "media-receipts"),
        cancelled = { cancelled.get() })

    fun transfer(network: Network?): Summary {
        if (storageTarget == StorageTarget.ANDROID_GALLERY && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            throw IOException("Shared-gallery import needs Android 10+; use the private gallery and Export.")
        val (endpoint, index) = loadIndex(network)
        MediaDestinationPolicy.requireUnique(index.items)
        if (index.items.isEmpty()) {
            throw IOException(
                if (index.declared == 0) "${endpoint.label} reported no media on the glasses."
                else "${endpoint.label} listed ${index.declared} files but none had a supported name " +
                    "(first unsupported: ${index.rejected.take(3).joinToString(", ")})."
            )
        }
        log("Found ${index.items.size} files on ${endpoint.host} (${endpoint.label}).")
        log("Files saved before verification receipts may be downloaded again once; originals on the glasses are untouched.")
        var downloaded = 0
        var skipped = 0
        var failed = 0
        index.items.forEachIndexed { i, item ->
            checkCancelled()
            val name = item.localName
            try {
                if (alreadySaved(item)) {
                    skipped++
                    log("${i + 1}/${index.items.size} Already saved: $name")
                } else {
                    log("Downloading: $name (${i + 1}/${index.items.size})")
                    if (storageTarget == StorageTarget.PRIVATE) downloadPrivate(network, endpoint, item)
                    else downloadOne(network, endpoint, item)
                    downloaded++
                    log("${i + 1}/${index.items.size} Saved: $name")
                }
            } catch (e: TransferCancelled) {
                throw e
            } catch (e: Exception) {
                failed++
                log("${i + 1}/${index.items.size} FAILED: $name — ${e.message}")
            }
        }
        return Summary(index.items.size, downloaded, skipped, failed)
    }

    private fun loadIndex(network: Network?): Pair<Endpoint, MediaIndex.Result> {
        val errors = mutableListOf<String>()
        for (endpoint in endpoints) {
            checkCancelled()
            try {
                log("Checking ${endpoint.label} at ${endpoint.host}…")
                val body = textFrom(network, endpoint.indexUrl())
                val result = MediaIndex.parse(body, endpoint.sizeUnit)
                if (result.items.isEmpty() && result.declared > 0)
                    throw IOException("no usable file names in ${result.declared} entries")
                return endpoint to result
            } catch (e: TransferCancelled) {
                throw e
            } catch (e: Exception) {
                errors.add("${endpoint.host}: ${e.message}")
            }
        }
        throw IOException(
            "Couldn't reach either media API. Join the glasses' Wi-Fi and retry. ${errors.joinToString("; ")}"
        )
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) throw TransferCancelled()
    }

    private fun textFrom(network: Network?, url: String): String {
        val conn = open(network, url, INDEX_TIMEOUT_MS, "Media index")
        try {
            boundedInput(conn, INDEX_TIMEOUT_MS, "Media index").use { input ->
                // 5 MiB cap for the JSON media listing to avoid unbounded memory use.
                val out = ByteArrayOutputStream()
                val block = ByteArray(8192)
                while (true) {
                    val n = input.read(block)
                    if (n < 0) break
                    if (n == 0) continue
                    if (out.size() + n > 5 * 1024 * 1024) throw IOException("Media index is too large")
                    out.write(block, 0, n)
                }
                return out.toString("UTF-8")
            }
        } finally { close(conn) }
    }

    private fun open(network: Network?, url: String, durationMs: Long, label: String): HttpURLConnection {
        val address = URL(url)
        val conn = (network?.openConnection(address) ?: address.openConnection()) as HttpURLConnection
        val watchdog = ConnectionWatchdog(durationMs, label) { conn.disconnect() }
        try {
            conn.connectTimeout = 7000
            conn.readTimeout = 20000
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.requestMethod = "GET"
            val status = conn.responseCode
            watchdog.check()
            if (status !in 200..299) throw IOException("HTTP $status for ${address.path}")
            watchdogs[conn] = watchdog
            return conn
        } catch (e: Exception) {
            watchdog.close()
            conn.disconnect()
            try { watchdog.check() } catch (timeout: IOException) {
                timeout.addSuppressed(e)
                throw timeout
            }
            throw e
        }
    }

    private fun close(conn: HttpURLConnection) {
        watchdogs.remove(conn)?.close()
        conn.disconnect()
    }

    private data class Destination(val collection: Uri, val folder: String, val mime: String, val type: String)

    private fun destination(item: MediaIndex.Item): Destination {
        val ext = item.localName.substringAfterLast('.').lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png" -> Destination(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_PICTURES}/GlassesDownloader/${item.localFolder}/",
                if (ext == "png") "image/png" else "image/jpeg", "image"
            )
            "mp4" -> Destination(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MOVIES}/GlassesDownloader/${item.localFolder}/",
                "video/mp4", "video"
            )
            else -> Destination(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${Environment.DIRECTORY_MUSIC}/GlassesDownloader/${item.localFolder}/",
                when (ext) { "aac" -> "audio/aac"; "wav" -> "audio/wav"; else -> "audio/mp4" }, "audio"
            )
        }
    }

    private fun alreadySaved(item: MediaIndex.Item): Boolean {
        if (storageTarget == StorageTarget.PRIVATE) {
            val file = PrivateMediaStorage.file(context, item.relativeName)
            return file.isFile && receipts.matches(item, file.length()) { file.inputStream() }
        }
        val d = destination(item)
        var found = false
        resolver.query(
            d.collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(item.localName, d.folder),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(0) || cursor.isNull(1)) continue
                val size = cursor.getLong(1)
                val uri = ContentUris.withAppendedId(d.collection, cursor.getLong(0))
                if (receipts.matches(item, size) {
                    resolver.openInputStream(uri) ?: throw IOException("Unable to read saved media")
                }) { found = true; break }
            }
        }
        return found
    }

    /** Save to the internal, sandboxed app gallery. No MediaStore or gallery-read permissions. */
    private fun downloadPrivate(network: Network?, endpoint: Endpoint, item: MediaIndex.Item) {
        val finalFile = PrivateMediaStorage.file(context, item.relativeName)
        if (!finalFile.parentFile!!.isDirectory && !finalFile.parentFile!!.mkdirs())
            throw IOException("Unable to create private media directory")
        val temp = File(finalFile.parentFile, finalFile.name + ".part")
        val conn = open(network, endpoint.fileUrl(item.remotePath), FILE_TIMEOUT_MS, "Media file ${item.localName}")
        try {
            var written = 0L
            var lastProgress = SystemClock.elapsedRealtime()
            boundedInput(conn, FILE_TIMEOUT_MS, "Media file ${item.localName}").use { input ->
                FileOutputStream(temp, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var first = true
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted)
                            throw TransferCancelled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (first) {
                            first = false
                            val type = when (finalFile.extension.lowercase(Locale.US)) {
                                "jpg", "jpeg", "png" -> "image"
                                "mp4" -> "video"
                                else -> "audio"
                            }
                            if (looksLikeError(buffer, n, type))
                                throw IOException("Server didn't return the expected media format")
                        }
                        output.write(buffer, 0, n)
                        written += n
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastProgress >= 3000L) {
                            log("$written bytes received for ${item.localName}; transfer still active.")
                            lastProgress = now
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (written == 0L) throw IOException("Empty file")
            MediaLengthPolicy.requireComplete(item.expectedSize, conn.contentLengthLong, written, item.sizeUnit)
            checkCancelled()
            val previous = PrivateMediaPublisher.publish(temp, finalFile,
                File(context.filesDir, "media-archive/${item.localFolder}"),
                cancelled = { cancelled.get() }) {
                receipts.record(item, finalFile.length()) { finalFile.inputStream() }
            }
            if (previous != null) log("Preserved different earlier phone copy at ${previous.absolutePath}; export it if needed.")
            parseTimestamp(item.timestamp)?.let { finalFile.setLastModified(it) }
        } finally {
            close(conn)
            if (temp.exists()) temp.delete()
        }
    }

    private fun downloadOne(network: Network?, endpoint: Endpoint, item: MediaIndex.Item) {
        val d = destination(item)
        val conn = open(network, endpoint.fileUrl(item.remotePath), FILE_TIMEOUT_MS, "Media file ${item.localName}")
        var mediaUri: Uri? = null
        try {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.localName)
                put(MediaStore.MediaColumns.MIME_TYPE, d.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, d.folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                val stamp = parseTimestamp(item.timestamp)
                if (stamp != null) {
                    put(MediaStore.MediaColumns.DATE_MODIFIED, stamp / 1000L)
                    when (d.type) {
                        "image" -> put(MediaStore.Images.Media.DATE_TAKEN, stamp)
                        "video" -> put(MediaStore.Video.Media.DATE_TAKEN, stamp)
                    }
                }
            }
            mediaUri = resolver.insert(d.collection, cv) ?: throw IOException("Unable to create MediaStore entry")
            val out = resolver.openOutputStream(mediaUri, "w") ?: throw IOException("Unable to write to MediaStore")
            var written = 0L
            boundedInput(conn, FILE_TIMEOUT_MS, "Media file ${item.localName}").use { input ->
                out.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var first = true
                    while (true) {
                        if (cancelled.get() || Thread.currentThread().isInterrupted) throw TransferCancelled()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (first) {
                            first = false
                            if (looksLikeError(buffer, count, d.type))
                                throw IOException("File response didn't look like ${d.type} media")
                        }
                        output.write(buffer, 0, count)
                        written += count
                    }
                    output.flush()
                }
            }
            if (written == 0L) throw IOException("Empty file")
            MediaLengthPolicy.requireComplete(item.expectedSize, conn.contentLengthLong, written, item.sizeUnit)
            checkCancelled()
            val complete = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(mediaUri, complete, null, null) == 0)
                throw IOException("Unable to publish downloaded file")
            val savedUri = mediaUri ?: throw IOException("Missing saved media entry")
            receipts.record(item, written) {
                resolver.openInputStream(savedUri) ?: throw IOException("Unable to verify saved media")
            }
            mediaUri = null // Ownership transferred to the user-visible media collection.
        } catch (e: Exception) {
            mediaUri?.let { try { resolver.delete(it, null, null) } catch (_: Exception) { } }
            throw e
        } finally { close(conn) }
    }

    private fun looksLikeError(buffer: ByteArray, n: Int, type: String): Boolean {
        // Reject obvious HTML/JSON error responses returned with HTTP 200.
        val prefix = String(buffer, 0, minOf(n, 32), Charsets.US_ASCII).trimStart()
        if (prefix.startsWith("<html", true) || prefix.startsWith("<!doctype", true) ||
            prefix.startsWith("{\"result\"") || prefix.startsWith("404")) return true
        if (type == "image" && n >= 2 && !(buffer[0] == 0xff.toByte() && buffer[1] == 0xd8.toByte())) {
            // PNG starts 89 50 4E 47; JPEG starts FF D8.
            if (!(n >= 4 && buffer[0] == 0x89.toByte() && buffer[1] == 0x50.toByte() &&
                buffer[2] == 0x4e.toByte() && buffer[3] == 0x47.toByte())) return true
        }
        return false
    }

    private fun parseTimestamp(text: String): Long? = try {
        if (text.length < 14) null
        else SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { isLenient = false }
            .parse(text.take(14))?.time
    } catch (_: Exception) { null }
}
