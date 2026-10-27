package io.github.danielphingston.metro.ui

/**
 * Follows the activity's top-resumed state. [update] returns true when it comes back after being
 * lost, which is how a launcher that was left (and returned to) is told from one that never was.
 */
internal class TopResumeTracker {
    private var lost = false

    fun update(isTop: Boolean): Boolean {
        if (!isTop) {
            lost = true
            return false
        }
        val regained = lost
        lost = false
        return regained
    }
}
