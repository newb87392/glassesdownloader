package com.open.glassesdownloader

/** Recheck on every attempt; grants can be revoked between the check and the platform call. */
object PickerScanAccess {
    fun run(missing: () -> Array<String>, request: (Array<String>) -> Unit,
            denied: () -> Unit, scan: () -> Unit) {
        try {
            val permissions = missing()
            if (permissions.isNotEmpty()) request(permissions) else scan()
        } catch (_: SecurityException) {
            denied()
        }
    }
}
