package io.github.danielphingston.metro.ui.search

import io.github.danielphingston.metro.ui.applist.AppGroups

/** An item that matched a search, with how well ([SearchRanking.EXACT] is best). */
data class Ranked<T>(val item: T, val rank: Int)

/**
 * Orders search matches the way people expect: exact, then starts with, then a word starts with,
 * then initials ("yt" for YouTube), then contains, then a small typo away. Free for everyone.
 */
object SearchRanking {
    const val EXACT = 0
    const val PREFIX = 1
    const val WORD_START = 2
    const val INITIALS = 3
    const val CONTAINS = 4
    const val FUZZY = 5

    /** How well [label] matches [query] (accents and case ignored), or null when it does not. */
    fun rank(label: String, query: String): Int? {
        val q = AppGroups.fold(query.trim())
        if (q.isEmpty()) return null
        val text = AppGroups.fold(label)
        return when {
            text == q -> EXACT
            text.startsWith(q) -> PREFIX
            startsWord(text, q) -> WORD_START
            matchesInitials(label, q) -> INITIALS
            text.contains(q) -> CONTAINS
            isTypo(text, q) -> FUZZY
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

    /** The words of [label]: split at anything that is not a letter or digit and at camelCase humps ("YouTube"). */
    fun words(label: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for ((i, c) in label.withIndex()) {
            if (!c.isLetterOrDigit()) {
                if (current.isNotEmpty()) out += current.toString().also { current.clear() }
                continue
            }
            val hump = i > 0 && c.isUpperCase() && label[i - 1].isLowerCase()
            if (hump && current.isNotEmpty()) out += current.toString().also { current.clear() }
            current.append(c)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.map(AppGroups::fold)
    }

    /** Whether the first letters of [label]'s words start with [q] ("gm" for Google Maps); needs two letters and two words. */
    private fun matchesInitials(label: String, q: String): Boolean {
        if (q.length < 2) return false
        val words = words(label)
        if (words.size < 2) return false
        return words.map { it.first() }.joinToString("").startsWith(q)
    }

    /**
     * Whether [q] is a small typo of the whole [text], of one of its words, or of the start of
     * one: one slip for 4 to 7 letters, two for 8 or more, none for shorter queries.
     */
    private fun isTypo(text: String, q: String): Boolean {
        val allowed = allowedTypos(q.length)
        if (allowed == 0) return false
        val candidates = words(text).toMutableList().apply { add(text) }
        return candidates.any { c ->
            editDistance(c, q) <= allowed || (c.length > q.length && editDistance(c.substring(0, q.length), q) <= allowed)
        }
    }

    /** How many typos a query of [length] letters may have. */
    fun allowedTypos(length: Int) = when {
        length >= 8 -> 2
        length >= 4 -> 1
        else -> 0
    }

    /** Edit distance between [a] and [b] where swapping two neighbouring letters counts as one slip. */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        var beforeLast = IntArray(b.length + 1)
        var last = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(row[j - 1] + 1, last[j] + 1, last[j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) best = minOf(best, beforeLast[j - 2] + 1)
                row[j] = best
            }
            beforeLast = last
            last = row
        }
        return last[b.length]
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
