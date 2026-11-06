package com.open.glassesdownloader

/** Rejects callbacks from a closed Wi-Fi request, even after a new request starts. */
internal class RequestGate {
    private var generation = 0L
    private var active = false

    @Synchronized fun begin(): Long {
        generation++
        active = true
        return generation
    }

    @Synchronized fun close() {
        generation++
        active = false
    }

    @Synchronized fun isCurrent(token: Long): Boolean = active && token == generation
}
