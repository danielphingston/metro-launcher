package io.github.danielphingston.metro.live.feed

import java.net.URI
import java.util.Locale

/**
 * What the user types for a feed tile, turned into the address it downloads. Any RSS or Atom
 * address works; Reddit has shorthands, since its feeds hide behind a ".rss" suffix:
 * "r/android", "/r/android/top", "reddit.com/r/android", "u/spez" and Reddit page links all
 * become the matching ".rss" feed.
 */
object FeedAddress {
    private const val REDDIT = "https://www.reddit.com"
    private val shorthand = Regex("^/?(r|u|user)/([A-Za-z0-9_+-]{2,})(/[A-Za-z0-9_/.-]*)?$")
    private val redditHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "m.reddit.com", "np.reddit.com")

    /** The feed address for [input], or null when it can't be one. Plain http is upgraded: Android refuses it. */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        shorthand.matchEntire(text)?.let { m ->
            val kind = if (m.groupValues[1] == "r") "r" else "user"
            return redditFeed("/$kind/${m.groupValues[2]}${m.groupValues[3]}")
        }
        val withScheme = when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring("http://".length)
            text.contains("://") -> return null
            else -> "https://$text"
        }
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (!host.contains('.')) return null
        if (host in redditHosts) {
            val path = uri.rawPath.orEmpty()
            if (path.isEmpty() || path == "/") return "$REDDIT/.rss"
            return redditFeed(path, uri.rawQuery)
        }
        return withScheme
    }

    /** A Reddit page [path] as its feed: "/r/android/top" -> ".../r/android/top/.rss". */
    private fun redditFeed(path: String, query: String? = null): String {
        val trimmed = path.trimEnd('/')
        val feed = when {
            trimmed.endsWith(".rss") -> trimmed
            trimmed.endsWith(".json") -> trimmed.removeSuffix(".json") + "/.rss"
            else -> "$trimmed/.rss"
        }
        return REDDIT + feed + (query?.let { "?$it" } ?: "")
    }

    /**
     * The tile's name for the feed at [url]: "r/android" for a subreddit, "u/name" for a user,
     * else the site's host without "www.".
     */
    fun label(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase(Locale.ROOT) ?: return "feed"
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() && it != ".rss" }
        if (host in redditHosts) {
            return when {
                parts.size >= 2 && parts[0] == "r" -> {
                    val subs = parts[1].split('+').filter { it.isNotEmpty() }
                    if (subs.size > 1) "r/${subs[0]} +${subs.size - 1}" else "r/${parts[1]}"
                }
                parts.size >= 2 && (parts[0] == "user" || parts[0] == "u") -> "u/${parts[1]}"
                else -> "reddit"
            }
        }
        return host.removePrefix("www.")
    }

    /** What to open when the tile has no headline to open yet: the Reddit page itself, else the site. */
    fun homePage(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url
        if (host.lowercase(Locale.ROOT) in redditHosts) return url.substringBefore('?').removeSuffix(".rss").trimEnd('/') + "/"
        return "${uri.scheme ?: "https"}://$host/"
    }

    /** The longest a subreddit name can be, and how many one tile may combine. */
    private val subName = Regex("^[A-Za-z0-9][A-Za-z0-9_]{1,20}$")
    const val MAX_SUBREDDITS = 30

    /**
     * A subreddit name from what the user typed: "android", "r/android", "/r/android/" or a
     * subreddit link. Null when it can't be one.
     */
    fun subreddit(input: String): String? {
        var text = input.trim()
        val uri = if (text.contains("reddit.com", ignoreCase = true)) runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() else null
        if (uri != null) text = uri.rawPath.orEmpty()
        text = text.trim('/').removePrefix("r/").removePrefix("R/").substringBefore('/')
        return text.takeIf { subName.matches(it) }
    }

    /** Several names typed at once: separated by spaces, commas or "+". Each piece is checked by [subreddit]. */
    fun splitSubreddits(input: String): List<String> =
        input.split(Regex("[\\s,+]+")).map { it.trim() }.filter { it.isNotEmpty() && it != "r/" }

    /** Which posts a Reddit tile shows. [path] is the listing in the address; hot is Reddit's default. */
    enum class RedditSort(val path: String?, val label: String) {
        HOT(null, "hot"), NEW("new", "new"), TOP("top", "top"), RISING("rising", "rising");

        companion object {
            fun fromPath(path: String?): RedditSort? = entries.firstOrNull { it.path == path }
        }
    }

    /** A Reddit feed made of [subreddits], in the order they were added. */
    data class RedditFeed(val subreddits: List<String>, val sort: RedditSort = RedditSort.HOT)

    /** The subreddits and sort of a Reddit subreddit feed at [url]; null for anything else (a user, the front page, another site). */
    fun reddit(url: String): RedditFeed? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.lowercase(Locale.ROOT) !in redditHosts || uri.rawQuery != null) return null
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() && it != ".rss" }
        if (parts.size !in 2..3 || parts[0] != "r") return null
        val subs = parts[1].split('+').filter { it.isNotEmpty() }
        if (subs.isEmpty() || subs.any { !subName.matches(it) }) return null
        val sort = RedditSort.fromPath(parts.getOrNull(2)) ?: return null
        return RedditFeed(subs, sort)
    }

    /** The feed address of [feed]: "https://www.reddit.com/r/android+pixel/new/.rss". */
    fun redditUrl(feed: RedditFeed): String =
        "$REDDIT/r/${feed.subreddits.joinToString("+")}" + (feed.sort.path?.let { "/$it" } ?: "") + "/.rss"

    /**
     * Whether [a] and [b] are the same feed, so a second tile for it would only repeat the first:
     * Reddit feeds with the same subreddits (any order or case) and sort, else the same address
     * up to the host's case and a trailing slash.
     */
    fun sameFeed(a: String, b: String): Boolean {
        val ra = reddit(a)
        val rb = reddit(b)
        if (ra != null || rb != null) {
            return ra != null && rb != null && ra.sort == rb.sort &&
                ra.subreddits.map { it.lowercase(Locale.ROOT) }.toSet() == rb.subreddits.map { it.lowercase(Locale.ROOT) }.toSet()
        }
        fun key(url: String): String {
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            return (uri.host?.lowercase(Locale.ROOT).orEmpty().removePrefix("www.")) + uri.rawPath.orEmpty().trimEnd('/') + (uri.rawQuery?.let { "?$it" } ?: "")
        }
        return key(a) == key(b)
    }

    /** How the address is shown in the field for editing: Reddit feeds as their shorthand. */
    fun shown(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.host?.lowercase(Locale.ROOT) !in redditHosts || uri.rawQuery != null) return url
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() && it != ".rss" }
        val kind = when (parts.getOrNull(0)) {
            "r" -> "r"
            "user", "u" -> "u"
            else -> return url
        }
        if (parts.size < 2) return url
        return (listOf("$kind/${parts[1]}") + parts.drop(2)).joinToString("/")
    }
}
