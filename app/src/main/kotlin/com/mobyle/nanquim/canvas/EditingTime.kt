package com.mobyle.nanquim.canvas

class EditingTime(
    private val idleLimitMillis: Long = DEFAULT_IDLE_LIMIT_MILLIS,
) {
    private var lastEditMillis: Long? = null
    private var pendingMillis = 0L

    fun edit(nowMillis: Long): Long {
        val last = lastEditMillis
        lastEditMillis = nowMillis
        if (last != null) pendingMillis += (nowMillis - last).coerceIn(0, idleLimitMillis)
        val seconds = pendingMillis / MILLIS_PER_SECOND
        pendingMillis -= seconds * MILLIS_PER_SECOND
        return seconds
    }

    fun reset() {
        lastEditMillis = null
        pendingMillis = 0
    }

    companion object {
        const val DEFAULT_IDLE_LIMIT_MILLIS = 60_000L
        private const val MILLIS_PER_SECOND = 1000L
    }
}
