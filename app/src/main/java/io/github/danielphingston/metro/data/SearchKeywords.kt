package io.github.danielphingston.metro.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Where a search keyword sends the text after it. Stored as JSON (see [SearchKeywords.encode]).
 * Everything here is pure, so the rules are unit tested without a phone.
 */
sealed interface KeywordTarget {
    /** What the chip and the settings row call it ("Amazon"). */
    val label: String

    /** One of the built-in "search in apps" apps; the Amazon country domain and the web fallbacks come with it. */
    data class BuiltIn(val app: SearchInApp) : KeywordTarget {
        override val label get() = app.label
    }

    /** The web search with the default engine. */
    data object Web : KeywordTarget {
        override val label get() = "web search"
    }

    /** The chosen assistant app (see [AssistantChoice]). */
    data object Assistant : KeywordTarget {
        override val label get() = "assistant"
    }

    /** An installed app that accepts a search; [label] is kept so a row still reads right if the app is gone. */
    data class InstalledApp(val packageName: String, override val label: String) : KeywordTarget

    /** A website: [template] is an http(s) address with one %s for the url-encoded text. */
    data class Website(val name: String, val template: String) : KeywordTarget {
        override val label get() = name
    }
}

/** A keyword (1 to 10 letters or digits), where it leads, and whether it is switched on. */
data class SearchKeyword(val keyword: String, val target: KeywordTarget, val enabled: Boolean = true) {
    /** "a → Amazon" */
    val summary: String get() = "$keyword → ${target.label}"
}

/** The keyword at the start of the query: [typed] as it was typed, [rest] the text after it (leading spaces dropped). */
data class KeywordMatch(val entry: SearchKeyword, val typed: String, val rest: String)

/** Why a keyword can't be saved. */
sealed interface KeywordError {
    val message: String

    data object Empty : KeywordError {
        override val message get() = "type a keyword"
    }

    data object Invalid : KeywordError {
        override val message get() = "use 1 to ${SearchKeywords.MAX_LENGTH} letters or digits, no spaces"
    }

    data class Taken(val by: SearchKeyword) : KeywordError {
        override val message get() = "already used for ${by.target.label}"
    }
}

/** What a typed change of the text next to the chip means. */
sealed interface ChipEdit {
    /** The text after the chip is now [rest]. */
    data class Text(val rest: String) : ChipEdit

    /** Backspace at the start of the text: the chip goes back to plain letters. */
    data object Remove : ChipEdit
}

object SearchKeywords {
    const val MAX_LENGTH = 10

    /** Stands in front of the text next to the chip so that backspace at its start has something to delete (and the keyboard something to report). */
    const val GUARD = '​'

    /** The characters that are invisible in the field: the guard and its relatives, which a paste could bring along. */
    private val zeroWidth = Regex("[\u200B\u200C\u200D\u2060\uFEFF]")

    /** [text] without zero-width characters, so a pasted or copied guard never reaches a URL, the history or the calculator. */
    fun stripZeroWidth(text: String): String = if (text.any { zeroWidth.matches(it.toString()) }) zeroWidth.replace(text, "") else text

    val defaults: List<SearchKeyword> = listOf(
        SearchKeyword("a", KeywordTarget.BuiltIn(SearchInApp.AMAZON)),
        SearchKeyword("yt", KeywordTarget.BuiltIn(SearchInApp.YOUTUBE)),
        SearchKeyword("ps", KeywordTarget.BuiltIn(SearchInApp.PLAY_STORE)),
        SearchKeyword("m", KeywordTarget.BuiltIn(SearchInApp.MAPS)),
        SearchKeyword("sp", KeywordTarget.BuiltIn(SearchInApp.SPOTIFY)),
        SearchKeyword("g", KeywordTarget.Web),
        SearchKeyword("ai", KeywordTarget.Assistant),
    )

    private fun valid(keyword: String) = keyword.length in 1..MAX_LENGTH && keyword.all { it.isLetterOrDigit() }

    /**
     * Whether [keyword] may be saved next to [others]. Keywords are unique ignoring case;
     * [editing] is the keyword being edited, which may keep its own text.
     */
    fun validate(keyword: String, others: List<SearchKeyword>, editing: String? = null): KeywordError? {
        val k = keyword.trim()
        if (k.isEmpty()) return KeywordError.Empty
        if (!valid(k)) return KeywordError.Invalid
        val clash = others.firstOrNull { it.keyword.equals(k, ignoreCase = true) && !it.keyword.equals(editing, ignoreCase = true) }
        return clash?.let { KeywordError.Taken(it) }
    }

    /**
     * The keyword [query] starts with, or null. Needs the feature [active] (unlocked and switched
     * on), an enabled keyword followed by a space, ignoring case; the longest keyword wins. The
     * word alone, without the space, never matches. [KeywordMatch.rest] may be empty.
     */
    fun detect(query: String, keywords: List<SearchKeyword>, active: Boolean): KeywordMatch? {
        if (!active) return null
        return keywords
            .filter { it.enabled && it.keyword.isNotEmpty() }
            .sortedByDescending { it.keyword.length }
            .firstNotNullOfOrNull { entry ->
                val n = entry.keyword.length
                if (query.length > n && query.regionMatches(0, entry.keyword, 0, n, ignoreCase = true) && query[n].isWhitespace()) {
                    KeywordMatch(entry, query.substring(0, n), query.substring(n + 1).trimStart())
                } else {
                    null
                }
            }
    }

    /** The query once the chip is typed over [match] with [rest]: the keyword as typed, a space, the text. */
    fun withRest(match: KeywordMatch, rest: String): String = match.typed + " " + rest

    /**
     * [list] with the entry typed as [original] replaced by [saved], or [saved] added at the end when [original] is null
     * or no longer there. Keyed by the keyword, not by identity, so a list that changed meanwhile still takes the edit.
     */
    fun upsert(list: List<SearchKeyword>, original: String?, saved: SearchKeyword): List<SearchKeyword> {
        val at = original?.let { o -> list.indexOfFirst { it.keyword.equals(o, ignoreCase = true) } } ?: -1
        return if (at >= 0) list.toMutableList().also { it[at] = saved } else list + saved
    }

    /** [list] without the entry typed as [keyword]; unchanged when it is gone already. */
    fun remove(list: List<SearchKeyword>, keyword: String): List<SearchKeyword> = list.filterNot { it.keyword.equals(keyword, ignoreCase = true) }

    /** [list] with the entry typed as [keyword] switched on or off. */
    fun setEnabled(list: List<SearchKeyword>, keyword: String, enabled: Boolean): List<SearchKeyword> =
        list.map { if (it.keyword.equals(keyword, ignoreCase = true)) it.copy(enabled = enabled) else it }

    /** The query once the chip turns back into letters: the keyword and the text, the space the backspace ate gone. */
    fun withoutChip(match: KeywordMatch): String = match.typed + match.rest

    /** Where the cursor belongs in [withoutChip]'s text: right after the keyword that was a chip. */
    fun cursorAfterChipRemoval(match: KeywordMatch): Int = match.typed.length

    /**
     * Reads what the field reports while a chip is showing. The field holds [GUARD] + [rest]; a
     * backspace at the start deletes the guard and nothing else ([raw] == [rest]); anything else
     * that loses the guard (a select-all delete, a paste over everything) just changes the text.
     */
    fun interpret(rest: String, raw: String): ChipEdit = when {
        raw.startsWith(GUARD) -> ChipEdit.Text(stripZeroWidth(raw.substring(1)))
        raw == rest -> ChipEdit.Remove
        else -> ChipEdit.Text(stripZeroWidth(raw))
    }

    // --- storage ------------------------------------------------------------------------------

    fun encode(keywords: List<SearchKeyword>): String = JSONArray().also { array ->
        keywords.forEach { entry ->
            array.put(
                JSONObject().put("k", entry.keyword).put("on", entry.enabled).put("t", encodeTarget(entry.target)),
            )
        }
    }.toString()

    private fun encodeTarget(target: KeywordTarget): JSONObject = when (target) {
        is KeywordTarget.BuiltIn -> JSONObject().put("type", "app").put("id", target.app.id)
        KeywordTarget.Web -> JSONObject().put("type", "web")
        KeywordTarget.Assistant -> JSONObject().put("type", "assistant")
        is KeywordTarget.InstalledApp -> JSONObject().put("type", "installed").put("pkg", target.packageName).put("label", target.label)
        is KeywordTarget.Website -> JSONObject().put("type", "site").put("name", target.name).put("template", target.template)
    }

    /**
     * The stored keywords. Missing or unreadable JSON gives the [defaults]; an entry that is
     * malformed, invalid or a duplicate (ignoring case) is dropped on its own. Never throws.
     */
    fun decode(json: String?): List<SearchKeyword> {
        if (json == null) return defaults
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return defaults
        val result = ArrayList<SearchKeyword>()
        for (i in 0 until array.length()) {
            val entry = runCatching { decodeEntry(array.getJSONObject(i)) }.getOrNull() ?: continue
            if (result.none { it.keyword.equals(entry.keyword, ignoreCase = true) }) result += entry
        }
        return result
    }

    private fun decodeEntry(o: JSONObject): SearchKeyword? {
        val keyword = o.getString("k").trim()
        if (!valid(keyword)) return null
        val target = decodeTarget(o.getJSONObject("t")) ?: return null
        return SearchKeyword(keyword, target, o.optBoolean("on", true))
    }

    private fun decodeTarget(o: JSONObject): KeywordTarget? = when (o.optString("type")) {
        "app" -> SearchInApp.entries.firstOrNull { it.id == o.optString("id") }?.let(KeywordTarget::BuiltIn)
        "web" -> KeywordTarget.Web
        "assistant" -> KeywordTarget.Assistant
        "installed" -> o.optString("pkg").takeIf { it.isNotBlank() }
            ?.let { KeywordTarget.InstalledApp(it, o.optString("label").ifBlank { it }) }
        "site" -> o.optString("template").takeIf { WebsiteTemplate.validate(it) == null }
            ?.let { KeywordTarget.Website(o.optString("name").trim().ifBlank { WebsiteTemplate.hostOf(it) }, it.trim()) }
        else -> null
    }
}

/** A website that takes the search text in its address, e.g. https://www.flipkart.com/search?q=%s. */
object WebsiteTemplate {
    /** A ready-made website offered when adding a keyword. */
    data class Preset(val name: String, val template: String)

    val presets = listOf(
        Preset("Flipkart", "https://www.flipkart.com/search?q=%s"),
        Preset("Wikipedia", "https://en.wikipedia.org/w/index.php?search=%s"),
    )

    private const val MARK = "%s"

    /** Null when [template] is usable, else what is wrong: http(s) with a host, and exactly one %s. */
    fun validate(template: String): String? {
        val t = template.trim()
        if (t.isEmpty()) return "type the address of the search page"
        if (t.any { it.isWhitespace() }) return "the address can't contain spaces"
        val afterScheme = when {
            t.startsWith("https://", ignoreCase = true) -> t.substring(8)
            t.startsWith("http://", ignoreCase = true) -> t.substring(7)
            else -> return "start with http:// or https://"
        }
        val host = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (host.isEmpty() || MARK in host) return "put %s in the search part of the address, not in the website name"
        return when (t.windowed(MARK.length).count { it == MARK }) {
            0 -> "add %s where the search text goes"
            1 -> null
            else -> "use %s only once"
        }
    }

    /** The address to open for [text]: the template with %s replaced by the percent-encoded text. Null if the template is invalid. */
    fun build(template: String, text: String): String? =
        if (validate(template) != null) null else template.trim().replace(MARK, encode(text))

    /** The host of [template] without "www.", for a name when none was typed. */
    fun hostOf(template: String): String =
        template.trim().substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' && it != '%' }
            .removePrefix("www.").ifBlank { "website" }

    /** Percent-encodes [text] as UTF-8; letters, digits and - . _ ~ stay, everything else (a space too) becomes %XX. */
    fun encode(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 128 && (ch.isLetterOrDigit() || ch in "-._~")) {
                append(ch)
            } else {
                append('%').append(String.format(Locale.ROOT, "%02X", c))
            }
        }
    }
}
