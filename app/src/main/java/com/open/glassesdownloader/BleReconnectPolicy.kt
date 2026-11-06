package com.open.glassesdownloader

/** Android's GATT 133 can be transient during back-to-back connections. */
internal object BleReconnectPolicy {
    const val MAX_RETRIES = 2
    fun shouldRetry(status: Int, gotSsid: Boolean, retries: Int): Boolean =
        status == 133 && !gotSsid && retries < MAX_RETRIES
}
