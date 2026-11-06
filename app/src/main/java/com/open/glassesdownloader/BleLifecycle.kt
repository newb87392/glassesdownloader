package com.open.glassesdownloader

/** Pure session gate. All callers (including scheduled tasks) run on the main thread. */
internal class BleLifecycle {
    private var generation = 0L
    private var active = false
    private var owner: Any? = null
    private var deadlineVersion = 0L

    fun bind(token: Long, identity: Any) {
        if (accepts(token)) owner = identity
    }

    fun end() {
        active = false
        owner = null
        cancelDeadline()
    }

    fun cancelDeadline() { deadlineVersion++ }

    /** Returns a one-shot runnable; cancellation works even if it is already queued. */
    fun deadline(token: Long, action: () -> Unit): () -> Unit {
        if (!accepts(token)) return {}
        val version = ++deadlineVersion
        return {
            if (accepts(token) && version == deadlineVersion) {
                cancelDeadline()
                action()
            }
        }
    }

    fun begin(): Long {
        generation++
        active = true
        owner = null
        return generation
    }

    fun accepts(token: Long, identity: Any? = null): Boolean =
        active && token == generation && (identity == null || identity === owner)
}
