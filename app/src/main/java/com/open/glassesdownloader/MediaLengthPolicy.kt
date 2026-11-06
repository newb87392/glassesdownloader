package com.open.glassesdownloader

import java.io.IOException

/** Remote size is required before a local hash receipt can certify a complete file. */
internal object MediaLengthPolicy {
    fun requireComplete(indexSize: Long?, contentLength: Long, received: Long,
                        indexUnit: MediaIndex.SizeUnit = MediaIndex.SizeUnit.BYTES) {
        val indexed = indexSize?.takeIf { it > 0L }
        val header = contentLength.takeIf { it > 0L }
        if (header == null && (indexed == null || indexUnit == MediaIndex.SizeUnit.KIB_FLOOR))
            throw IOException("Exact HTTP Content-Length is required when the index size is rounded or missing; cannot verify completeness.")
        if (indexed != null && header != null && !indexMatches(indexed, indexUnit, header))
            throw IOException("Remote index and HTTP file sizes disagree ($indexed $indexUnit vs $header bytes); no completion receipt saved.")
        val expected = header ?: indexed!!
        if (received != expected)
            throw IOException("Incomplete download: $received of $expected bytes")
    }

    fun indexMatches(indexSize: Long?, unit: MediaIndex.SizeUnit, actualBytes: Long): Boolean =
        indexSize == null || when (unit) {
            MediaIndex.SizeUnit.BYTES -> indexSize == actualBytes
            MediaIndex.SizeUnit.KIB_FLOOR -> actualBytes >= 0L && actualBytes / 1024L == indexSize
        }
}
