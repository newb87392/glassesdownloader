package com.open.glassesdownloader

/** Reuse an existing Android 8/9 configuration instead of deleting a network owned by Settings. */
internal object LegacyWifiPolicy {
    fun existingNetworkId(ssid: String, saved: List<Pair<String, Int>>): Int? =
        saved.firstOrNull { it.first == "\"$ssid\"" }?.second

    fun isJoined(ssid: String, currentSsid: String?, completed: Boolean, ipAddress: Int): Boolean =
        completed && ipAddress != 0 && currentSsid?.trim('"') == ssid
}

internal enum class JoinDecision { JOINED, WAIT, RETRY, FAIL }

/** Monotonic per-attempt deadline: a late successful association beats a timeout. */
internal object LegacyJoinRetry {
    const val MAX_ATTEMPTS = 3
    const val ATTEMPT_MS = 12_000L
    fun decide(nowMs: Long, attemptStartMs: Long, attempt: Int, joined: Boolean): JoinDecision = when {
        joined -> JoinDecision.JOINED
        nowMs - attemptStartMs < ATTEMPT_MS -> JoinDecision.WAIT
        attempt < MAX_ATTEMPTS -> JoinDecision.RETRY
        else -> JoinDecision.FAIL
    }
}
