package io.github.danielphingston.metro.ui.applist

import io.github.danielphingston.metro.data.AppInfo
import java.text.Normalizer
import java.util.Locale

/** Alphabetical grouping for the app list's letter tiles: "#", a–z, then a globe for other scripts. */
object AppGroups {
    const val SYMBOLS = "#"
    const val OTHER = "🌐"
    val ALL: List<String> = listOf(SYMBOLS) + ('a'..'z').map(Char::toString) + OTHER

    fun groupOf(label: String): String {
        val first = label.firstOrNull { it.isLetterOrDigit() } ?: return SYMBOLS
        if (first.isDigit()) return SYMBOLS
        val base = fold(first.toString()).firstOrNull() ?: return OTHER
        return if (base in 'a'..'z') base.toString() else OTHER
    }

    /** How many apps the "recently added" group shows, and how recent counts as recent. */
    const val RECENT_COUNT = 3
    const val RECENT_DAYS = 5L

    /**
     * Apps installed in the last [RECENT_DAYS] days, newest first, at most [RECENT_COUNT].
     * Preinstalled system apps are left out: on a new phone they would all be "recent".
     */
    fun recentlyAdded(apps: List<AppInfo>, now: Long): List<AppInfo> {
        val since = now - RECENT_DAYS * 24 * 60 * 60 * 1000
        return apps.filter { it.isUninstallable && it.installTime in since..now }
            .sortedByDescending { it.installTime }
            .take(RECENT_COUNT)
    }

    fun matches(label: String, query: String): Boolean {
        val q = fold(query.trim())
        return q.isEmpty() || fold(label).contains(q)
    }

    /** Lowercase and strip accents, so "é" groups under "e" and "cafe" finds "Café". */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
}
