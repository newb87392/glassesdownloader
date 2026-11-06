package com.open.glassesdownloader

import java.io.IOException
import java.util.Locale

/** Refuse ambiguous destinations rather than silently replacing one camera original. */
internal object MediaDestinationPolicy {
    fun requireUnique(items: List<MediaIndex.Item>) {
        val collision = items.groupBy { it.relativeName.lowercase(Locale.US) }
            .entries.firstOrNull { (_, group) -> group.map { it.remotePath }.distinct().size > 1 }
        if (collision != null) throw IOException(
            "Media destination collision: ${collision.value.first().relativeName} maps to multiple files " +
                "on the glasses. Nothing was downloaded. Export existing files and contact support; " +
                "do not delete originals from the glasses."
        )
    }
}
