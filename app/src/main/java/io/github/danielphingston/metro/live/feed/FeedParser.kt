package io.github.danielphingston.metro.live.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One headline. [image] is a picture for it (a thumbnail), [publishedAt] epoch millis. */
data class FeedItem(
    val title: String,
    val link: String?,
    val author: String? = null,
    val publishedAt: Long? = null,
    val image: String? = null,
    /** Lemmy's score and comment count, when the feed says them. */
    val points: Int? = null,
    val comments: Int? = null,
    /**
     * The instance the author belongs to, when the feed names them by profile link (Lemmy). A post
     * by someone on the feed's own instance was written there, so its link is its own address.
     */
    val authorHome: String? = null,
    /** The picture's size in bytes as the feed declares it (an enclosure's length), when it does. */
    val imageBytes: Long? = null,
)

data class Feed(val title: String?, val items: List<FeedItem>)

/**
 * Reads RSS 2.0, RSS 1.0 (RDF) and Atom, Lemmy's community feeds included. Lenient: unknown elements are
 * skipped, an item without a title is dropped, and HTML in titles is reduced to text. Throws
 * [FeedFormatException] when the document is no feed at all.
 */
object FeedParser {
    const val MAX_ITEMS = 25

    /**
     * The longest headline, author and feed name kept. A tile shows at most five lines (about 150
     * characters) and Lemmy titles stop at 200, so nothing shown is lost; a feed can't make the
     * tile lay out megabytes of text at every flip.
     */
    const val MAX_TITLE = 300
    const val MAX_AUTHOR = 100
    const val MAX_FEED_TITLE = 200

    /** The longest <img> tag searched for its picture. */
    private const val MAX_IMG_TAG = 4096

    /**
     * Every pattern here runs in time proportional to its input: a feed may be 2 MB, and patterns
     * that backtrack (an unbounded run before a part that must follow) took hours on crafted text.
     */
    private val imgSrc = Regex("""src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val tags = Regex("<[^<>]*>")
    private val space = Regex("\\s+")

    fun parse(xml: String): Feed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))
        var sawRoot = false
        var feedTitle: String? = null
        val items = ArrayList<FeedItem>()
        var event = prolog(parser)
        while (event != XmlPullParser.END_DOCUMENT && items.size < MAX_ITEMS) {
            if (event == XmlPullParser.START_TAG) {
                when (local(parser.name)) {
                    "rss", "feed", "RDF", "channel" -> sawRoot = true
                    "item", "entry" -> {
                        sawRoot = true
                        readItem(parser)?.let(items::add)
                    }
                    "title" -> if (feedTitle == null && sawRoot) feedTitle = cap(clean(readText(parser)), MAX_FEED_TITLE)
                }
            }
            event = parser.next()
        }
        if (!sawRoot) throw FeedFormatException("no rss or atom feed")
        return Feed(feedTitle?.ifEmpty { null }, items)
    }

    /** Reads the item whose start tag the parser is on, up to its end tag. */
    private fun readItem(parser: XmlPullParser): FeedItem? {
        val depth = parser.depth
        var title: String? = null
        var link: String? = null
        var author: String? = null
        var date: Long? = null
        var image: String? = null
        var htmlImage: String? = null
        var counts: Pair<Int, Int>? = null
        var authorHome: String? = null
        var imageBytes: Long? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val raw = parser.name
                when (local(raw)) {
                    // media:title (inside media:group) only stands in for a missing title.
                    "title" -> if (':' !in raw || title == null) title = clean(readText(parser))
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        val href = parser.getAttributeValue(null, "href")
                        if (href != null) {
                            // Atom: the alternate link is the page; "enclosure" is an attached file.
                            if (rel == "enclosure" && image == null && isImage(parser.getAttributeValue(null, "type"))) image = href
                            if ((rel == null || rel == "alternate") && link == null) link = href
                            skip(parser)
                        } else {
                            readText(parser).trim().takeIf { it.isNotEmpty() }?.let { if (link == null) link = it }
                        }
                    }
                    "guid" -> {
                        val permalink = parser.getAttributeValue(null, "isPermaLink") != "false"
                        val text = readText(parser).trim()
                        if (link == null && permalink && text.startsWith("http")) link = text
                    }
                    "author", "creator" -> {
                        val (name, home) = readAuthor(parser)
                        if (author == null) {
                            author = name
                            authorHome = home
                        }
                    }
                    "pubDate", "published", "updated", "date" -> {
                        val parsed = parseDate(readText(parser).trim())
                        // Prefer when it was published over when it was last updated.
                        if (date == null || local(raw) != "updated") date = parsed ?: date
                    }
                    "thumbnail" -> {
                        parser.getAttributeValue(null, "url")?.let { if (image == null) image = it }
                        skip(parser)
                    }
                    "content" -> {
                        // media:content carries a url; Atom content is HTML that may hold the picture.
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null) {
                            val medium = parser.getAttributeValue(null, "medium")
                            if (image == null && (medium == "image" || isImage(parser.getAttributeValue(null, "type")))) image = url
                            skip(parser)
                        } else {
                            if (htmlImage == null) htmlImage = firstImage(readText(parser))
                        }
                    }
                    "description", "summary", "encoded" -> {
                        val html = readText(parser)
                        if (htmlImage == null) htmlImage = firstImage(html)
                        if (counts == null) counts = counts(html)
                    }
                    "enclosure" -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && image == null && isImage(parser.getAttributeValue(null, "type"))) {
                            image = url
                            imageBytes = parser.getAttributeValue(null, "length")?.trim()?.toLongOrNull()?.takeIf { it > 0 }
                        }
                        skip(parser)
                    }
                }
            }
            event = parser.next()
        }
        val text = title?.takeIf { it.isNotEmpty() }?.let { cap(it, MAX_TITLE) } ?: return null
        val picture = (image ?: htmlImage)?.let(::unescape)?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        // Only web links are opened from a tile: a feed can't make a tap run "intent:" or "javascript:".
        val web = link?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
        // The declared size belongs to the enclosure; it says nothing about a picture found elsewhere.
        val declared = imageBytes?.takeIf { picture != null && picture == image?.let(::unescape) }
        return FeedItem(text, web, author?.let { cap(it, MAX_AUTHOR) }, date, picture, counts?.first, counts?.second, authorHome, declared)
    }

    /**
     * Reads up to the root element and returns its START_TAG. A document whose DOCTYPE declares
     * entities is refused before any is used: the parser expands them, and a few nested ones
     * (the "billion laughs") turn a 1 KB feed into gigabytes of text. Feeds don't declare their
     * own entities; a plain DOCTYPE (RSS 0.91's) is fine.
     */
    private fun prolog(parser: XmlPullParser): Int {
        var event = runCatching { parser.nextToken() }.getOrElse { throw FeedFormatException("not XML") }
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.DOCDECL && parser.text.orEmpty().contains("ENTITY")) {
                throw FeedFormatException("declares entities")
            }
            event = runCatching { parser.nextToken() }.getOrElse { throw FeedFormatException("not XML") }
        }
        return event
    }

    /** [text] cut to [max] characters (never inside a surrogate pair), with an ellipsis when cut. */
    internal fun cap(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = max - 1
        if (Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    /** The element name without its namespace prefix ("media:thumbnail" -> "thumbnail"). */
    private fun local(name: String?): String = name.orEmpty().substringAfter(':')

    private fun isImage(type: String?): Boolean = type?.startsWith("image/") == true

    /** The text of the element the parser is on (nested markup included as text); leaves it on its end tag. */
    private fun readText(parser: XmlPullParser): String {
        val depth = parser.depth
        val out = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) out.append(parser.text)
            event = parser.next()
        }
        return out.toString()
    }

    private fun skip(parser: XmlPullParser) {
        readText(parser)
    }

    /**
     * An author: RSS has plain text (often "mail@example.com (Name)"), Atom a person whose <name>
     * is the part to show (as "/u/someone" or a profile link). Leaves the parser on the author's end tag.
     */
    private fun readAuthor(parser: XmlPullParser): Pair<String?, String?> {
        val depth = parser.depth
        val text = StringBuilder()
        var name: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && local(parser.name) == "name") {
                name = readText(parser)
            } else if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                if (parser.depth == depth) text.append(parser.text)
            }
            event = parser.next()
        }
        val raw = clean(name ?: text.toString())
        // "mail@example.com (Name)" shows the name.
        val shown = mailName.matchEntire(raw)?.groupValues?.get(1) ?: raw
        // Lemmy names the author by their profile link: "https://lemmy.ml/u/someone" shows "someone".
        if (shown.startsWith("https://") || shown.startsWith("http://")) {
            val home = runCatching { java.net.URI(shown).host?.lowercase(Locale.ROOT) }.getOrNull()
            return shown.trimEnd('/').substringAfterLast('/').ifEmpty { null } to home
        }
        return shown.removePrefix("/").ifEmpty { null } to null
    }

    /** "mail@example.com (Name)"; the address can't hold a second "@", so there is one way to match. */
    private val mailName = Regex("""^[^\s@]+@\S+\s+\((.+)\)$""")

    // Bounded runs: an unbounded digit or space run before a part that must follow backtracks.
    private val countsLine = Regex("""(?<!\d)(-?\d{1,10}) points?\s{0,20}\|\s{0,20}(?:<a[^<>]{0,1000}>)?\s{0,20}(\d{1,10}) comments?""", RegexOption.IGNORE_CASE)

    /** Lemmy's "479 points | 94 comments" line, as (points, comments). */
    internal fun counts(html: String): Pair<Int, Int>? =
        countsLine.find(html)?.let { m -> m.groupValues[1].toIntOrNull()?.let { p -> m.groupValues[2].toIntOrNull()?.let { c -> p to c } } }

    /**
     * The picture of the first <img> tag that has one (its last src, as lazy-loading pages put
     * the real picture in data-src after a placeholder). Each tag is searched on its own, once.
     */
    internal fun firstImage(html: String): String? {
        var from = 0
        while (true) {
            val start = html.indexOf("<img", from, ignoreCase = true)
            if (start < 0) return null
            val end = html.indexOf('>', start)
            if (end < 0) return null
            if (end - start <= MAX_IMG_TAG) {
                imgSrc.findAll(html.subSequence(start, end)).lastOrNull()?.let { return it.groupValues[1] }
            }
            from = end + 1
        }
    }

    /** Text for a title: tags removed, entities decoded (feeds often escape them twice), spaces collapsed. */
    internal fun clean(text: String): String = unescape(unescape(text).replace(tags, " ")).replace(space, " ").trim()

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|#39);")

    internal fun unescape(text: String): String = if ('&' !in text) text else entity.replace(text) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos", "#39" -> "'"
            "nbsp" -> " "
            else -> {
                val code = if (e.startsWith("#x")) e.substring(2).toIntOrNull(16) else e.substring(1).toIntOrNull()
                code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }

    /** RFC 822 (RSS) or ISO 8601 (Atom, Dublin Core) to epoch millis; null when neither. */
    internal fun parseDate(text: String): Long? {
        if (text.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
        // RFC 822 with a zone name (EST, GMT...) or without the weekday.
        for (pattern in listOf("EEE, d MMM yyyy HH:mm:ss zzz", "d MMM yyyy HH:mm:ss zzz", "EEE, d MMM yyyy HH:mm zzz")) {
            runCatching {
                return ZonedDateTime.parse(text, DateTimeFormatter.ofPattern(pattern, Locale.US)).toInstant().toEpochMilli()
            }
        }
        return null
    }
}

class FeedFormatException(message: String) : Exception(message)
