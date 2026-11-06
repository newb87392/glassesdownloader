package com.open.glassesdownloader

/** Bound UI history so repeated transfers cannot make a giant TextView or UI hierarchy. */
internal object UserLog {
    fun append(previous: String, line: String, maxChars: Int = 30_000): String {
        require(maxChars > 0)
        return (previous + line + "\n").takeLast(maxChars)
    }
}
