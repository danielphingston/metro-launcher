package io.github.danielphingston.metro.live.feed

import java.net.URI
import java.util.Locale

/**
 * What the user types for a feed tile, turned into the addresses it downloads. Two kinds:
 *
 * - A Lemmy community: "!linux@lemmy.ml", "linux@lemmy.ml", "c/linux@lemmy.ml" or a community
 *   link ("lemmy.world/c/linux@lemmy.ml" goes to lemmy.ml, the community's own instance, since
 *   an instance only serves the feeds of its own communities). Its feed is
 *   "https://lemmy.ml/feeds/c/linux.xml?sort=Hot".
 * - Any RSS or Atom address.
 *
 * Reddit ended its RSS feeds on 13 November 2026; Reddit addresses are refused with that reason.
 */
object FeedAddress {
    const val REDDIT_ENDED = "Reddit ended its RSS feeds in November 2026"

    /** How many feeds one tile may combine. */
    const val MAX_FEEDS = 12

    private val redditHosts = setOf("reddit.com", "www.reddit.com", "old.reddit.com", "new.reddit.com", "m.reddit.com", "np.reddit.com", "redd.it")
    private val host = Regex("^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")
    private val name = Regex("^[A-Za-z0-9_]{1,50}$")
    private val handle = Regex("^!?(?:/?c/)?([A-Za-z0-9_]{1,50})@([A-Za-z0-9.-]+)$")
    private val communityPath = Regex("^/c/([A-Za-z0-9_]{1,50})(?:@([A-Za-z0-9.-]+))?/?$")
    private val feedPath = Regex("^/feeds/c/([A-Za-z0-9_]{1,50})\\.xml$")

    /** A Lemmy community [name] living on [instance]. */
    data class Community(val name: String, val instance: String) {
        override fun toString() = "!$name@$instance"
    }

    /** Which posts a community feed lists; [param] is Lemmy's own name for it. */
    enum class LemmySort(val param: String, val label: String) {
        HOT("Hot", "hot"), ACTIVE("Active", "active"), NEW("New", "new"), TOP_DAY("TopDay", "top today");

        companion object {
            fun fromParam(param: String?): LemmySort? = entries.firstOrNull { it.param.equals(param, ignoreCase = true) }
        }
    }

    /** What one typed entry means. */
    sealed interface Entry {
        data class Lemmy(val community: Community) : Entry
        data class Address(val url: String) : Entry
        data class Invalid(val message: String) : Entry
    }

    fun parse(input: String): Entry {
        val text = input.trim()
        if (text.isEmpty()) return Entry.Invalid("type a community or an address")
        if (isReddit(text)) return Entry.Invalid(REDDIT_ENDED)
        community(text)?.let { return Entry.Lemmy(it) }
        if (name.matches(text.removePrefix("!").removePrefix("c/"))) {
            return Entry.Invalid("add the community's instance, like ${text.removePrefix("!").removePrefix("c/")}@lemmy.world")
        }
        return normalize(text)?.let { Entry.Address(it) } ?: Entry.Invalid("that isn't a community or a web address")
    }

    /** Several entries typed at once, separated by spaces or commas (neither occurs in a community or an address). */
    fun split(input: String): List<String> = input.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

    /** The community [text] names, as a handle or a link; null when it names none. */
    fun community(text: String): Community? {
        handle.matchEntire(text.trim())?.let { m ->
            val instance = m.groupValues[2].lowercase(Locale.ROOT)
            return if (host.matches(instance)) Community(m.groupValues[1].lowercase(Locale.ROOT), instance) else null
        }
        val uri = uri(text) ?: return null
        val at = uri.host?.lowercase(Locale.ROOT) ?: return null
        val path = uri.rawPath.orEmpty()
        communityPath.matchEntire(path)?.let { m ->
            val home = m.groupValues[2].ifEmpty { at }.lowercase(Locale.ROOT)
            return if (host.matches(home)) Community(m.groupValues[1].lowercase(Locale.ROOT), home) else null
        }
        feedPath.matchEntire(path)?.let { m -> return Community(m.groupValues[1].lowercase(Locale.ROOT), at) }
        return null
    }

    /** The feed of [community] sorted by [sort]. */
    fun lemmyUrl(community: Community, sort: LemmySort = LemmySort.HOT): String =
        "https://${community.instance}/feeds/c/${community.name}.xml?sort=${sort.param}"

    /** The community and sort of a community feed address; null for any other address. */
    fun lemmy(url: String): Pair<Community, LemmySort>? {
        val uri = uri(url) ?: return null
        val m = feedPath.matchEntire(uri.rawPath.orEmpty()) ?: return null
        val sortParam = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("sort=") }?.removePrefix("sort=")
        val sort = if (sortParam == null) LemmySort.HOT else LemmySort.fromParam(sortParam) ?: LemmySort.HOT
        return Community(m.groupValues[1].lowercase(Locale.ROOT), uri.host.lowercase(Locale.ROOT)) to sort
    }

    /** [url] with [sort], if it is a community feed; any other address as it is. */
    fun withSort(url: String, sort: LemmySort): String = lemmy(url)?.let { (community, _) -> lemmyUrl(community, sort) } ?: url

    fun isReddit(text: String): Boolean {
        val t = text.trim()
        if (Regex("^/?r/[A-Za-z0-9_]+.*").matches(t)) return true
        return uri(t)?.host?.lowercase(Locale.ROOT) in redditHosts
    }

    /** An RSS or Atom address for [input], or null when it can't be one. Plain http is upgraded: Android refuses it. */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            text.startsWith("https://", ignoreCase = true) -> text
            text.startsWith("http://", ignoreCase = true) -> "https://" + text.substring("http://".length)
            text.contains("://") -> return null
            else -> "https://$text"
        }
        val parsed = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val h = parsed.host ?: return null
        return if (host.matches(h)) withScheme else null
    }

    private fun uri(text: String): URI? {
        val t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val withScheme = when {
            t.startsWith("https://", ignoreCase = true) || t.startsWith("http://", ignoreCase = true) -> t
            t.contains("://") -> return null
            else -> "https://$t"
        }
        return runCatching { URI(withScheme) }.getOrNull()?.takeIf { it.host != null }
    }

    /** The host of [url] when it is a site's front page ("https://programming.dev/"), else null. */
    fun siteHost(url: String): String? {
        val uri = uri(url) ?: return null
        val path = uri.rawPath.orEmpty()
        if ((path.isNotEmpty() && path != "/") || uri.rawQuery != null) return null
        return uri.host.lowercase(Locale.ROOT)
    }

    /** One feed's name: "c/linux" for a community, else the site's host without "www.". */
    fun label(url: String): String {
        lemmy(url)?.let { (community, _) -> return "c/${community.name}" }
        return uri(url)?.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: "feed"
    }

    /** A tile's name: its feed's, or the first one's and how many more. */
    fun label(urls: List<String>): String = when (urls.size) {
        0 -> "feed"
        1 -> label(urls[0])
        else -> "${label(urls[0])} +${urls.size - 1}"
    }

    /** How a feed is listed in the editor: "!linux@lemmy.ml", or the address. */
    fun shown(url: String): String = lemmy(url)?.first?.toString() ?: url

    /** What to open when the tile has no headline to open yet: the community's page, else the site. */
    fun homePage(url: String): String {
        lemmy(url)?.let { (community, _) -> return "https://${community.instance}/c/${community.name}" }
        val uri = uri(url) ?: return url
        return "https://${uri.host}/"
    }

    /** What makes two addresses the same feed: a community's name, instance and sort, else the address without case in the host or a trailing slash. */
    fun key(url: String): String {
        lemmy(url)?.let { (community, sort) -> return "lemmy:$community:${sort.param}" }
        val uri = uri(url) ?: return url
        return uri.host.lowercase(Locale.ROOT).removePrefix("www.") + uri.rawPath.orEmpty().trimEnd('/') + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    /** Whether two tiles' feeds are the same, in any order. */
    fun sameFeeds(a: List<String>, b: List<String>): Boolean = a.map(::key).toSet() == b.map(::key).toSet()
}
