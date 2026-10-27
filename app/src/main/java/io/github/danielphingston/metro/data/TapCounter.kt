package io.github.danielphingston.metro.data

/**
 * Counts quick repeated taps: [tap] returns true on the [target]th tap, each made within
 * [windowMs] of the one before. A slower tap starts the count again; reaching the target resets it.
 */
class TapCounter(private val target: Int = 7, private val windowMs: Long = 3_000) {
    private var count = 0
    private var last = 0L

    fun tap(nowMs: Long): Boolean {
        count = if (count > 0 && nowMs - last <= windowMs) count + 1 else 1
        last = nowMs
        if (count < target) return false
        count = 0
        return true
    }
}
