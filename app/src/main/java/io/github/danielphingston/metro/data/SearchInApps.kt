package io.github.danielphingston.metro.data

/**
 * The apps "search in apps" can search, built in. Each has its own switch (see
 * [LauncherSettings.searchInApps]); apps found at run time that accept a search are switched
 * separately by package (see [LauncherSettings.searchInOtherApps]). A built-in app that isn't
 * installed is skipped, except where a web page can stand in (YouTube, Amazon).
 */
enum class SearchInApp(
    val id: String,
    val label: String,
    val packageName: String,
    val defaultOn: Boolean,
    /** Other packages the same app ships under, such as Amazon's India build. */
    val altPackages: List<String> = emptyList(),
) {
    YOUTUBE("youtube", "YouTube", "com.google.android.youtube", true),
    PLAY_STORE("play_store", "Play Store", "com.android.vending", true),
    MAPS("maps", "Maps", "com.google.android.apps.maps", false),
    SPOTIFY("spotify", "Spotify", "com.spotify.music", false),
    AMAZON("amazon", "Amazon", "com.amazon.mShop.android.shopping", false, listOf("in.amazon.mShop.android.shopping")),
    ;

    /** Every package this app may be installed as, the main one first. */
    val packages: List<String> get() = listOf(packageName) + altPackages

    companion object {
        /** The built-in apps that are on until the user switches them off. */
        val defaults: Set<SearchInApp> = entries.filterTo(LinkedHashSet()) { it.defaultOn }
    }
}

/** How the "search in" strip in the results is ordered. */
enum class SearchInOrder(val id: String, val label: String) {
    MOST_USED("most_used", "most used"),
    CUSTOM("custom", "custom"),
    ;

    companion object {
        val default = MOST_USED

        fun fromId(id: String?): SearchInOrder = entries.firstOrNull { it.id == id } ?: default
    }
}

/** One thing the strip can search: [id] is stable ([SearchInOrdering.builtInId], [SearchInOrdering.otherId]) and keys the counts and the custom order. */
data class SearchInTarget(val id: String, val label: String)

/**
 * The pure rules of the "search in" strip: target ids, the two orders, the stored custom order
 * and the settings filter. No storage, so all of it is tested directly.
 */
object SearchInOrdering {
    fun builtInId(app: SearchInApp) = app.id

    fun otherId(packageName: String) = "app:$packageName"

    /**
     * [targets] (given in their natural order: the built-in apps, then the others by name) in the
     * order the strip shows. Most used: the target used from search most often first, ties by the
     * [custom] order. Custom: the [custom] order. In both, a target missing from [custom] comes
     * after the listed ones, in its natural order.
     */
    fun ordered(targets: List<SearchInTarget>, order: SearchInOrder, custom: List<String>, uses: Map<String, Int>): List<SearchInTarget> {
        val position = custom.withIndex().associate { (i, id) -> id to i }
        val byCustom = compareBy<SearchInTarget> { position[it.id] ?: Int.MAX_VALUE }
        val comparator = if (order == SearchInOrder.MOST_USED) {
            compareByDescending<SearchInTarget> { uses[it.id] ?: 0 }.then(byCustom)
        } else {
            byCustom
        }
        // sortedWith is stable, which is what keeps the natural order for targets neither list knows.
        return targets.sortedWith(comparator)
    }

    /** [stored] limited to the ids in [known], then every other known id in natural order: what the custom list shows. */
    fun complete(stored: List<String>, known: List<String>): List<String> {
        val set = known.toSet()
        val kept = stored.filter { it in set }.distinct()
        return kept + (known - kept.toSet())
    }

    /** [ids] with [id] moved by [delta] places (negative is up); unchanged at the ends or when [id] is not there. */
    fun move(ids: List<String>, id: String, delta: Int): List<String> {
        val from = ids.indexOf(id)
        val to = (from + delta).coerceIn(0, (ids.size - 1).coerceAtLeast(0))
        if (from < 0 || from == to) return ids
        return ids.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** The custom order as stored: one id per line (ids never contain a line break). */
    fun encode(ids: List<String>): String = ids.joinToString("\n")

    fun decode(stored: String?): List<String> = stored.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

/** The filter field at the top of the long settings lists. */
object ListFilter {
    /**
     * Whether an entry with these [texts] (its name, keyword, ...) matches the typed [filter]: every
     * word of the filter must start a word of, or sit inside, one of the texts, ignoring case.
     * A blank filter matches everything.
     */
    fun matches(filter: String, vararg texts: String): Boolean {
        val words = filter.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = texts.joinToString(" ").lowercase()
        return words.all { it in haystack }
    }
}
