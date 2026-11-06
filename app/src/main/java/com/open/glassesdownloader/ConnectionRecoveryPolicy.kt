package com.open.glassesdownloader

/** A full BLE/AP restart is safe only before any HTTP transfer has begun. */
internal object ConnectionRecoveryPolicy {
    const val MAX_SESSION_RETRIES = 2
    fun shouldRestart(retries: Int, transferStarted: Boolean, styleOperation: Boolean): Boolean =
        retries < MAX_SESSION_RETRIES && !transferStarted && !styleOperation
}
