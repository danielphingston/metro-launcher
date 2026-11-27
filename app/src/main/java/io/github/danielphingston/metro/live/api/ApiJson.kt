package io.github.danielphingston.metro.live.api

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Reading a downloaded JSON document: parsing it safely, walking paths into it, and listing its fields for the editor. */
object ApiJson {
    /** Deeper than this is refused before parsing: JSONTokener recurses once per level. */
    const val MAX_DEPTH = 64
    private const val MAX_FIELDS = 400
    private const val FIELD_DEPTH = 8
    private const val SAMPLE_CHARS = 60

    /**
     * [text] as a JSONObject, JSONArray or plain value. Throws [JSONException] when it isn't JSON
     * (an HTML error page, say) or nests deeper than [MAX_DEPTH]. Blocking: call off the main thread.
     */
    fun parse(text: String): Any? {
        checkDepth(text)
        val tokener = JSONTokener(text.trimStart('\uFEFF'))
        val value = tokener.nextValue()
        if (tokener.nextClean() != 0.toChar()) throw JSONException("text after the JSON")
        if (value is String && !text.trimStart().startsWith("\"")) throw JSONException("not JSON")
        // JSONTokener reads a bare NaN or Infinity as a number; no API means that.
        if (value is Double && (value.isNaN() || value.isInfinite())) throw JSONException("not JSON")
        return value
    }

    private fun checkDepth(text: String) {
        var depth = 0
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) throw JSONException("nested too deeply")
                '}', ']' -> depth--
            }
            i++
        }
    }

    /** The steps of a path written as text: "a.b[0]['c d']" -> [a, b, 0, "c d"]. A leading "$." is ignored. */
    fun steps(path: String): List<Any> {
        val steps = ArrayList<Any>()
        val p = path.trim().removePrefix("$").removePrefix("@").removePrefix(".")
        var i = 0
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) {
                val w = word.toString()
                // Digits too long for an index stay text: they then lead nowhere instead of throwing.
                steps += if (w.all(Char::isDigit) && steps.isNotEmpty()) w.toIntOrNull() ?: w else w
                word.clear()
            }
        }
        while (i < p.length) {
            val c = p[i]
            when {
                c == '.' -> flush()
                c == '[' -> {
                    flush()
                    val end = p.indexOf(']', i)
                    if (end < 0) return steps
                    val inside = p.substring(i + 1, end).trim()
                    steps += inside.toIntOrNull() ?: inside.trim('\'', '"')
                    i = end
                }
                else -> word.append(c)
            }
            i++
        }
        flush()
        return steps
    }

    /** What [steps] lead to inside [value]; null when the path doesn't exist. Text keys also read numbered list items ("items.0"). */
    fun at(value: Any?, steps: List<Any>): Any? {
        var here = value
        for (step in steps) {
            here = when (here) {
                is JSONObject -> here.opt(step.toString())
                is JSONArray -> (step as? Int ?: step.toString().toIntOrNull())?.let { index ->
                    // A negative index counts from the end: items[-1] is the last.
                    here.opt(if (index < 0) here.length() + index else index)
                }
                else -> null
            }
            if (here == null || here == JSONObject.NULL) return null
        }
        return here
    }

    /** One field the editor offers: its [path] as a formula reads it, a short [sample] of its value, and whether it is a [list]. */
    data class Field(val path: String, val sample: String, val list: Boolean = false, val count: Int = 0)

    /**
     * The fields of [root], in document order, for picking: every value, plus each list (with
     * how many items it has). Inside a list only its first item is listed, as "items[0].name".
     * At most [MAX_FIELDS] fields, [FIELD_DEPTH] levels deep.
     */
    fun fields(root: Any?): List<Field> {
        val out = ArrayList<Field>()
        fun walk(value: Any?, path: String, depth: Int) {
            if (out.size >= MAX_FIELDS || depth > FIELD_DEPTH) return
            when (value) {
                is JSONObject -> {
                    val keys = value.keys().asSequence().toList()
                    for (key in keys) walk(value.opt(key), join(path, key), depth + 1)
                }
                is JSONArray -> {
                    if (path.isNotEmpty()) out += Field(path, "${value.length()} items", list = true, count = value.length())
                    if (value.length() > 0) walk(value.opt(0), "$path[0]", depth + 1)
                }
                else -> if (path.isNotEmpty()) out += Field(path, sample(value))
            }
        }
        if (root is JSONArray) out += Field("$", "${root.length()} items", list = true, count = root.length())
        walk(root, "", 0)
        return out
    }

    /** The fields of one item of the list at [listPath], with paths relative to the item, for a row's templates. */
    fun rowFields(root: Any?, listPath: String): List<Field> {
        val list = if (listPath == "$" || listPath.isBlank()) root else at(root, steps(listPath))
        val first = (list as? JSONArray)?.takeIf { it.length() > 0 }?.opt(0) ?: return emptyList()
        if (first !is JSONObject && first !is JSONArray) return listOf(Field("@", sample(first)))
        return fields(first)
    }

    /** The items of the list at [listPath] in [root] ("$" for a document that is itself a list), up to [max]. */
    fun items(root: Any?, listPath: String, max: Int): List<Any?> {
        val list = (if (listPath == "$" || listPath.isBlank()) root else at(root, steps(listPath))) as? JSONArray ?: return emptyList()
        return (0 until minOf(list.length(), max)).map { list.opt(it) }
    }

    /** What a value in the answer is, for the field tree. */
    enum class Kind { OBJECT, LIST, TEXT, NUMBER, BOOLEAN, NULL }

    /**
     * One entry of the field tree: the value at [path] (as a formula reads it), shown as [key],
     * its [kind], a short [sample] of a plain value, and how many fields or items it holds ([size]).
     */
    data class Node(val path: String, val key: String, val kind: Kind, val sample: String = "", val size: Int = 0) {
        val expandable: Boolean get() = (kind == Kind.OBJECT || kind == Kind.LIST) && size > 0
    }

    /** The entry for [value] at [path]. */
    fun node(value: Any?, path: String, key: String): Node = when (value) {
        is JSONObject -> Node(path, key, Kind.OBJECT, size = value.length())
        is JSONArray -> Node(path, key, Kind.LIST, size = value.length())
        null, JSONObject.NULL -> Node(path, key, Kind.NULL, "null")
        is String -> Node(path, key, Kind.TEXT, sample(value))
        is Boolean -> Node(path, key, Kind.BOOLEAN, value.toString())
        is Number -> Node(path, key, Kind.NUMBER, sample(value))
        else -> Node(path, key, Kind.TEXT, sample(value))
    }

    /**
     * What opening the entry at [path] (holding [value]) shows: an object's fields in order, or a
     * list's first [maxItems] items as "[0]", "[1]"…, each with the path a formula reads.
     */
    fun children(value: Any?, path: String, maxItems: Int = MAX_ITEMS_SHOWN): List<Node> = when (value) {
        is JSONObject -> value.keys().asSequence().map { k -> node(value.opt(k), join(path, k), k) }.toList()
        is JSONArray -> (0 until minOf(value.length(), maxItems)).map { i -> node(value.opt(i), "$path[$i]", "[$i]") }
        else -> emptyList()
    }

    /**
     * Fields and lists anywhere in [root] whose name, path or value contains [query] (ignoring
     * case), best first: a name that is the query, then names that start with it, then the
     * rest; shallower before deeper; then in document order. At most [max]. Looks at up to
     * [MAX_VISITS] values, so a huge answer can't stall the editor; [Search.complete] says whether
     * every match is shown.
     */
    fun search(root: Any?, query: String, max: Int = 60): Search {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return Search(emptyList(), true)
        class Hit(val node: Node, val rank: Int, val depth: Int, val order: Int)
        val hits = ArrayList<Hit>()
        var visits = 0
        fun walk(value: Any?, path: String, key: String, depth: Int): Boolean {
            if (++visits > MAX_VISITS || hits.size >= MAX_HITS) return false
            val n = node(value, path, key)
            if (path.isNotEmpty()) {
                val k = key.lowercase()
                val plain = n.kind != Kind.OBJECT && n.kind != Kind.LIST
                val rank = when {
                    k == q -> 0
                    k.startsWith(q) -> 1
                    k.contains(q) -> 2
                    path.lowercase().contains(q) -> 3
                    plain && n.sample.lowercase().contains(q) -> 4
                    else -> -1
                }
                if (rank >= 0) hits += Hit(n, rank, depth, hits.size)
            }
            when (value) {
                is JSONObject -> for (k in value.keys()) if (!walk(value.opt(k), join(path, k), k, depth + 1)) return false
                is JSONArray -> for (i in 0 until value.length()) if (!walk(value.opt(i), "$path[$i]", "[$i]", depth + 1)) return false
            }
            return true
        }
        val walkedAll = walk(root, "", "", 0)
        val best = hits.sortedWith(compareBy<Hit>({ it.rank }, { it.depth }, { it.order })).take(max).map { it.node }
        return Search(best, walkedAll && hits.size <= max)
    }

    data class Search(val nodes: List<Node>, val complete: Boolean)

    /** How many plain values and lists [root] holds, counted up to [MAX_VISITS]: the picker's heading. */
    fun count(root: Any?): Int {
        var n = 0
        fun walk(value: Any?): Boolean {
            if (n > MAX_VISITS) return false
            when (value) {
                is JSONObject -> for (k in value.keys()) if (!walk(value.opt(k))) return false
                is JSONArray -> { n++; for (i in 0 until value.length()) if (!walk(value.opt(i))) return false }
                else -> n++
            }
            return true
        }
        walk(root)
        return if (root is JSONArray) n - 1 else n
    }

    /** The value at [path], "$" or "" being [root] itself. */
    fun valueAt(root: Any?, path: String): Any? = if (path.isBlank() || path == "$") root else at(root, steps(path))

    /**
     * Fields to start a list's rows with, read against one [item]: a name-like text (name, title,
     * label…, else the first text) and a number to go beside it, each at most two levels in.
     * A plain item (a string or number) is "@" itself.
     */
    fun suggestRow(item: Any?): Pair<String?, String?> {
        if (item !is JSONObject && item !is JSONArray) return (if (item == null) null else "@") to null
        val leaves = ArrayList<Node>()
        fun walk(value: Any?, path: String, depth: Int) {
            for (child in children(value, path, 1)) {
                if (child.expandable) {
                    if (depth < 1) walk(valueAt(item, child.path), child.path, depth + 1)
                } else {
                    leaves += child
                }
            }
        }
        walk(item, "", 0)
        val texts = leaves.filter { it.kind == Kind.TEXT && !it.sample.startsWith("\"http") }
        val primary = NAME_KEYS.firstNotNullOfOrNull { k -> texts.firstOrNull { it.key.equals(k, ignoreCase = true) } } ?: texts.firstOrNull()
        val secondary = leaves.firstOrNull { it.kind == Kind.NUMBER }
        return primary?.path to secondary?.path
    }

    private val NAME_KEYS = listOf("name", "title", "label", "headline", "displayName", "display_name", "text", "id")

    /** The entries that hold [path], outermost first, ending with [path]: what the tree opens to show it. */
    fun ancestors(path: String): List<String> {
        val out = ArrayList<String>()
        var quote: Char? = null
        for (i in path.indices) {
            val c = path[i]
            when {
                quote != null -> if (c == quote && path.getOrNull(i - 1) != '\\') quote = null
                c == '\'' || c == '"' -> quote = c
                (c == '.' || c == '[') && i > 0 -> out += path.substring(0, i)
            }
        }
        out += path
        return out.distinct()
    }

    /** A list shows this many of its items in the tree; search reaches the rest. */
    const val MAX_ITEMS_SHOWN = 5

    /** Search keeps no more matches than this before ranking them. */
    private const val MAX_HITS = 5_000

    /** Search and counting look at no more values than this. */
    const val MAX_VISITS = 50_000

    private val plainKey = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    private fun join(path: String, key: String): String = when {
        plainKey.matches(key) -> if (path.isEmpty()) key else "$path.$key"
        else -> "$path['" + key.replace("'", "\\'") + "']"
    }

    private fun sample(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> "\"" + value.take(SAMPLE_CHARS) + if (value.length > SAMPLE_CHARS) "…\"" else "\""
        else -> value.toString().take(SAMPLE_CHARS)
    }
}
