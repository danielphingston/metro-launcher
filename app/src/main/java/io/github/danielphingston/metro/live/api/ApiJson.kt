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
                steps += if (w.all(Char::isDigit) && steps.isNotEmpty()) w.toInt() else w
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
