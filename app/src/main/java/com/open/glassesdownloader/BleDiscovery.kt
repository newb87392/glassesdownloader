package com.open.glassesdownloader

/** AA12 is mandatory; an optional name prefix only narrows discovery. */
internal object BleDiscovery {
    fun matches(advertisesService: Boolean, name: String, prefix: String): Boolean =
        advertisesService && (prefix.trim().isEmpty() || name.startsWith(prefix.trim(), ignoreCase = true))
}
