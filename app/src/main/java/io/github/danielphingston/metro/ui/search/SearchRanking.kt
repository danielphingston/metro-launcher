package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.ui.applist.AppGroups

/** An item that matched a search, with how well ([SearchRanking.EXACT] is best). */
data class Ranked<T>(val item: T, val rank: Int)

/** Orders search matches the way people expect: exact, then starts with, then a word starts with, then contains. */
object SearchRanking {
    const val EXACT = 0
    const val PREFIX = 1
    const val WORD_START = 2
    const val CONTAINS = 3

    /** How well [label] matches [query] (accents and case ignored), or null when it does not. */
    fun rank(label: String, query: String): Int? {
        val q = AppGroups.fold(query.trim())
        if (q.isEmpty()) return null
        val text = AppGroups.fold(label)
        return when {
            text == q -> EXACT
            text.startsWith(q) -> PREFIX
            startsWord(text, q) -> WORD_START
            text.contains(q) -> CONTAINS
            else -> null
        }
    }

    /** Whether [q] begins at the start of any word of [text], e.g. "app" in "my app". */
    private fun startsWord(text: String, q: String): Boolean {
        var from = text.indexOf(q, 1)
        while (from > 0) {
            if (!text[from - 1].isLetterOrDigit()) return true
            from = text.indexOf(q, from + 1)
        }
        return false
    }

    /**
     * The best [limit] of [items] for [query], best match first and alphabetical within a rank.
     * An item matches through any of its [labels] (e.g. a shortcut's short and long label).
     */
    fun <T> top(items: List<T>, query: String, limit: Int, labels: (T) -> List<String>): List<Ranked<T>> {
        if (query.isBlank()) return emptyList()
        return items
            .mapNotNull { item ->
                val names = labels(item)
                val rank = names.mapNotNull { rank(it, query) }.minOrNull() ?: return@mapNotNull null
                Triple(item, rank, AppGroups.fold(names.firstOrNull().orEmpty()))
            }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .take(limit)
            .map { Ranked(it.first, it.second) }
    }
}
