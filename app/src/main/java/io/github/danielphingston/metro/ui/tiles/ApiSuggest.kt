package io.github.danielphingston.metro.ui.tiles

import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.Times
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Fills a tile from a fetched answer without AI: with no [prompt] it picks a likely name, number,
 * picture, line and list; with one ("pikachu with its weight and stats") it finds the fields the
 * words name, by key, path or value, and places each by what it holds. Everything it returns is a
 * path that exists in the answer, so the result always renders; it never throws.
 */
internal object ApiSuggest {
    /** What a build came to: the templates to apply, a line for each, and the prompt's words that matched nothing. */
    data class Result(val templates: Map<ApiTarget, String>, val unmatched: List<String> = emptyList()) {
        val isEmpty: Boolean get() = templates.isEmpty()
    }

    /** One value in the answer, outside lists or in a list's first item ([inList]). */
    private data class Leaf(val path: String, val key: String, val value: Any?, val depth: Int, val inList: Boolean) {
        val text: String? get() = value as? String
        val isNumber: Boolean get() = value is Number && (value as Number).toDouble().let { !it.isNaN() && !it.isInfinite() }
        val isLink: Boolean get() = text?.startsWith("https://") == true || text?.startsWith("http://") == true
        val isImage: Boolean
            get() = isLink && (IMAGE_EXT.containsMatchIn(text!!.substringBefore('?').lowercase(Locale.ROOT)) || IMAGE_KEYS.any { key.lowercase(Locale.ROOT).contains(it) })
        val isTime: Boolean
            get() = when {
                text != null -> text!!.length in 10..40 && text!!.first().isDigit() && Times.parse(text) != null
                else -> TIME_KEYS.any { key.lowercase(Locale.ROOT).contains(it) } && isNumber && Times.parse(value) != null
            }
    }

    private data class ListEntry(val path: String, val key: String, val size: Int, val depth: Int, val inList: Boolean)

    private const val MAX_LEAVES = 3_000
    private const val MAX_DEPTH = 10

    private val IMAGE_EXT = Regex("\\.(png|jpe?g|webp|gif|svg|avif)$")
    private val IMAGE_KEYS = listOf("image", "img", "avatar", "thumbnail", "thumb", "picture", "photo", "sprite", "icon", "logo", "artwork", "poster", "cover")
    private val TIME_KEYS = listOf("time", "date", "start", "end", "at", "until", "expires", "deadline", "updated", "created", "timestamp")
    private val NAME_KEYS = listOf("name", "title", "label", "headline", "displayname", "display_name", "full_name", "text")
    private val LINE_KEYS = listOf("description", "summary", "subtitle", "status", "state", "type", "category", "location", "venue", "city")
    private val LINK_KEYS = listOf("html_url", "web_url", "permalink", "link", "homepage", "website")
    private val STOP = setOf(
        "the", "a", "an", "and", "or", "with", "its", "it", "of", "show", "me", "my", "in", "on", "for", "to", "plus", "as", "is",
        "tile", "tiles", "big", "value", "values", "list", "rows", "row", "each", "their", "his", "her", "this", "that", "from", "by",
        "all", "some", "what", "how", "many", "much", "current", "latest", "also", "then", "at", "top",
    )
    private val PICTURE_WORDS = setOf("picture", "image", "photo", "pic", "sprite", "icon", "logo", "avatar", "art", "artwork", "thumbnail")
    private val COUNTDOWN_WORDS = setOf("countdown", "until", "left", "remaining", "next", "starts", "start", "time")
    private val AGO_WORDS = setOf("ago", "updated", "since", "last")

    fun suggest(root: Any?, prompt: String = ""): Result = try {
        build(root, prompt)
    } catch (e: Throwable) {
        Result(emptyMap())
    }

    private fun build(root: Any?, prompt: String): Result {
        if (root !is JSONObject && root !is JSONArray) return Result(emptyMap())
        val leaves = ArrayList<Leaf>()
        val lists = ArrayList<ListEntry>()
        collect(root, "", "", 0, false, leaves, lists)
        val words = words(prompt)
        val out = LinkedHashMap<ApiTarget, String>()
        val unmatched = ArrayList<String>()
        val used = HashSet<String>()
        val top = leaves.filter { !it.inList }

        fun place(leaf: Leaf, word: String?) {
            used += leaf.path
            val field = "{${leaf.path}}"
            when {
                leaf.isImage && ApiTarget.IMAGE !in out -> out[ApiTarget.IMAGE] = field
                leaf.isImage -> Unit
                leaf.isTime && word != null && word in AGO_WORDS -> putLine(out, "{ago(${leaf.path})}")
                leaf.isTime && ApiTarget.VALUE !in out -> out[ApiTarget.VALUE] = "{countdown(${leaf.path})}"
                leaf.isNumber && ApiTarget.VALUE !in out -> out[ApiTarget.VALUE] = field
                leaf.isNumber -> putLine(out, "${label(leaf.key)} $field")
                leaf.text != null && ApiTarget.TITLE !in out && !leaf.isLink -> out[ApiTarget.TITLE] = field
                leaf.isLink && LINK_KEYS.any { leaf.key.equals(it, ignoreCase = true) } && ApiTarget.LINK !in out -> out[ApiTarget.LINK] = field
                else -> putLine(out, field)
            }
        }

        fun useList(list: ListEntry, words: List<String>) {
            out[ApiTarget.LIST] = list.path
            used += list.path
            val item = ApiJson.items(root, list.path, 1).firstOrNull()
            // Words that name a field of the items set the rows; else a name and a number.
            val inItem = ArrayList<Leaf>()
            if (item is JSONObject || item is JSONArray) collect(item, "", "", 0, false, inItem, ArrayList())
            val named = words.mapNotNull { w -> best(inItem, w) }.distinct()
            val (primary, secondary) = ApiJson.suggestRow(item)
            val text = named.firstOrNull { it.text != null && !it.isLink }?.path ?: primary
            val number = named.firstOrNull { it.isNumber }?.path ?: secondary
            val image = named.firstOrNull { it.isImage }?.path ?: inItem.firstOrNull { it.isImage && it.depth <= 2 }?.path
            text?.let { out[ApiTarget.ROW_PRIMARY] = "{$it}" }
            number?.takeIf { it != text }?.let { out[ApiTarget.ROW_SECONDARY] = "{$it}" }
            image?.let { out[ApiTarget.ROW_IMAGE] = "{$it}" }
            if (ApiTarget.ROW_PRIMARY !in out) out[ApiTarget.ROW_PRIMARY] = "{@}"
        }

        if (words.isEmpty()) {
            // No prompt: a likely name, number, picture, line and list.
            top.filter { it.text != null && !it.isLink }.minWithOrNull(compareBy({ nameRank(it.key) }, { it.depth }))
                ?.takeIf { nameRank(it.key) < NAME_KEYS.size || it.depth <= 1 }?.let { place(it, null) }
            top.firstOrNull { it.isTime && it.depth <= 2 }?.let { place(it, null) }
                ?: top.filter { it.isNumber && it.depth <= 2 && !it.key.equals("id", true) }.minByOrNull { it.depth }?.let { place(it, null) }
            top.firstOrNull { it.isImage }?.let { place(it, null) }
            top.firstOrNull { it.path !in used && it.text != null && !it.isLink && LINE_KEYS.any { k -> it.key.lowercase(Locale.ROOT).contains(k) } }?.let { putLine(out, "{${it.path}}") }
            lists.filter { !it.inList && it.size > 0 }.maxByOrNull { it.size - it.depth }?.let { useList(it, emptyList()) }
            return Result(out)
        }

        // A prompt: each word names a field, a list, or what kind of thing to show.
        val rest = ArrayList<String>()
        for (word in words) {
            when {
                word in PICTURE_WORDS -> top.firstOrNull { it.isImage && it.path !in used }?.let { place(it, word) } ?: unmatched.add(word)
                word in COUNTDOWN_WORDS && best(top, word) == null ->
                    top.firstOrNull { it.isTime && it.path !in used }?.let { place(it, word) } ?: unmatched.add(word)
                word in AGO_WORDS && best(top, word) == null ->
                    top.firstOrNull { it.isTime && it.path !in used }?.let { place(it, word) } ?: unmatched.add(word)
                else -> rest += word
            }
        }
        var listUsed = false
        for ((i, word) in rest.withIndex()) {
            val list = bestList(lists, word)
            val leaf = best(top, word)
            when {
                // A word naming a list makes it the rows; the words after it name the rows' fields.
                list != null && !listUsed && (leaf == null || score(list.key, list.path, word) <= score(leaf.key, leaf.path, word)) -> {
                    useList(list, rest.drop(i + 1))
                    listUsed = true
                }
                leaf != null && leaf.path !in used -> place(leaf, word)
                leaf != null -> Unit
                else -> {
                    // A value ("pikachu") names the thing itself: its field is the title.
                    val byValue = top.firstOrNull { it.text != null && it.text!!.equals(word, ignoreCase = true) }
                    if (byValue != null && ApiTarget.TITLE !in out) {
                        out[ApiTarget.TITLE] = "{${byValue.path}}"
                        used += byValue.path
                    } else if (!listUsed || lists.none { it.path == out[ApiTarget.LIST] }) {
                        unmatched += word
                    }
                }
            }
        }
        // The rows' words were read with their list; only words that matched nothing stay unmatched.
        val rowWords = out[ApiTarget.LIST]?.let { path -> rest.dropWhile { bestList(lists, it)?.path != path }.drop(1) }.orEmpty()
        unmatched.removeAll { it in rowWords && out[ApiTarget.ROW_PRIMARY] != "{@}" }
        if (ApiTarget.TITLE !in out) {
            top.filter { it.text != null && !it.isLink && it.path !in used }.minWithOrNull(compareBy({ nameRank(it.key) }, { it.depth }))
                ?.takeIf { nameRank(it.key) < NAME_KEYS.size }?.let { out[ApiTarget.TITLE] = "{${it.path}}" }
        }
        return Result(out, unmatched.distinct())
    }

    private fun putLine(out: MutableMap<ApiTarget, String>, template: String) {
        when {
            ApiTarget.LINE1 !in out -> out[ApiTarget.LINE1] = template
            ApiTarget.LINE2 !in out -> out[ApiTarget.LINE2] = template
            else -> out[ApiTarget.LINE2] = out[ApiTarget.LINE2] + " · " + template
        }
    }

    private fun label(key: String): String = key.replace('_', ' ').replace('-', ' ').lowercase(Locale.ROOT)

    private fun nameRank(key: String): Int = NAME_KEYS.indexOfFirst { it.equals(key, ignoreCase = true) }.let { if (it < 0) NAME_KEYS.size else it }

    /** The prompt's words that can name something: lower case, without filler, singular too. */
    internal fun words(prompt: String): List<String> =
        prompt.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}_-]+")).filter { it.length > 1 && it !in STOP }.take(20).distinct()

    /** How well [word] names a field: 0 its key, 1 its key without an s or with one, 2 a part of its key, 3 its path; lower is better. */
    private fun score(key: String, path: String, word: String): Int {
        val k = key.lowercase(Locale.ROOT)
        val w = word.removeSuffix("s")
        return when {
            k == word -> 0
            k.removeSuffix("s") == w -> 1
            k.split('_', '-').any { it == word || it.removeSuffix("s") == w } -> 2
            k.contains(w) -> 3
            path.lowercase(Locale.ROOT).contains(w) -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun best(leaves: List<Leaf>, word: String): Leaf? =
        leaves.map { it to score(it.key, it.path, word) }.filter { it.second != Int.MAX_VALUE }
            .minWithOrNull(compareBy({ it.second }, { it.first.depth }))?.first

    private fun bestList(lists: List<ListEntry>, word: String): ListEntry? =
        lists.filter { !it.inList && it.size > 0 }.map { it to score(it.key, it.path, word) }.filter { it.second <= 2 }
            .minWithOrNull(compareBy({ it.second }, { it.first.depth }))?.first

    private fun collect(value: Any?, path: String, key: String, depth: Int, inList: Boolean, leaves: MutableList<Leaf>, lists: MutableList<ListEntry>) {
        if (leaves.size >= MAX_LEAVES || depth > MAX_DEPTH) return
        when (value) {
            is JSONObject -> for (child in ApiJson.children(value, path)) {
                collect(value.opt(child.key), child.path, child.key, depth + 1, inList, leaves, lists)
            }
            is JSONArray -> {
                if (path.isNotEmpty()) lists += ListEntry(path, key, value.length(), depth, inList)
                if (value.length() > 0) collect(value.opt(0), "$path[0]", key, depth + 1, true, leaves, lists)
            }
            null, JSONObject.NULL -> Unit
            else -> if (path.isNotEmpty()) leaves += Leaf(path, key, value, depth, inList)
        }
    }
}
