package io.github.danielphingston.metro.ui.applist

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
