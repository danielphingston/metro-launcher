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
                parts.size >= 2 && parts[0] == "r" -> "r/${parts[1]}"
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

    /** How the address is shown in the field for editing: Reddit feeds as their shorthand. */
    fun shown(url: String): String {
        val label = label(url)
        return if (label.startsWith("r/") || label.startsWith("u/")) {
            val uri = runCatching { URI(url) }.getOrNull()
            val parts = uri?.rawPath.orEmpty().split('/').filter { it.isNotEmpty() && it != ".rss" }
            (listOf(label) + parts.drop(2)).joinToString("/")
        } else {
            url
        }
    }
}
