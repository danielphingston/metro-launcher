package io.github.danielphingston.metro.ui.tiles

import io.github.danielphingston.metro.data.ApiTileJson
import io.github.danielphingston.metro.live.api.ApiAddress
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.FormulaContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A tile's setup as JSON, pasted, shared in, or written by the on-device AI: read ([parse]), then
 * held against the real answer ([check]), so a field it names that isn't in the answer, or a
 * formula that can't be read, is left out with a note instead of reaching the tile. Header values
 * (API keys) are never imported: only their names, so they are typed on the phone and kept
 * encrypted. Nothing here throws.
 */
internal object ApiImport {
    /** Pasted or shared text past this is not read. */
    const val MAX_CHARS = 65_536

    /** A tile's setup, by editor target; an empty [url] keeps the editor's own. */
    data class Draft(
        val name: String = "",
        val url: String = "",
        val headers: List<String> = emptyList(),
        val tile: Map<ApiTarget, String> = emptyMap(),
        val detailUrl: String = "",
        val detail: Map<ApiTarget, String> = emptyMap(),
        val refreshMinutes: Int? = null,
        val liveSeconds: Int? = null,
    )

    sealed interface Parsed {
        /** Read: [notes] says what was left out and why. */
        data class Ok(val draft: Draft, val notes: List<String> = emptyList()) : Parsed
        data class Failed(val message: String) : Parsed
    }

    /** The keys of a view, as the import format and [prompt] write them. */
    val KEYS: Map<String, ApiTarget> = linkedMapOf(
        "title" to ApiTarget.TITLE,
        "value" to ApiTarget.VALUE,
        "line1" to ApiTarget.LINE1,
        "line2" to ApiTarget.LINE2,
        "image" to ApiTarget.IMAGE,
        "link" to ApiTarget.LINK,
        "list" to ApiTarget.LIST,
        "rowText" to ApiTarget.ROW_PRIMARY,
        "rowDetail" to ApiTarget.ROW_SECONDARY,
        "rowImage" to ApiTarget.ROW_IMAGE,
    )

    fun parse(text: String): Parsed = try {
        read(text)
    } catch (e: Throwable) {
        Parsed.Failed("can't read that as a tile")
    }

    private fun read(text: String): Parsed {
        if (text.length > MAX_CHARS) return Parsed.Failed("that's too long for a tile setup (over 64 KB)")
        // Chatbots wrap JSON in ``` fences and a sentence or two: take the outermost object.
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return Parsed.Failed("no tile setup found: paste the JSON, starting with {")
        val obj = runCatching { ApiJson.parse(text.substring(start, end + 1)) as? JSONObject }.getOrNull()
            ?: return Parsed.Failed("that isn't valid JSON: check it was copied whole")
        // A tile copied out of a Metro launcher layout or backup.
        if (obj.optString("type") == ApiTileJson.TYPE || obj.optJSONObject("tile")?.has("slots") == true) {
            val api = ApiTileJson.decode(obj) ?: return Parsed.Failed("that tile's setup can't be read: its address must be https")
            return Parsed.Ok(
                Draft(api.name, api.tile.url, api.headers, templatesOf(api.tile), api.detail.url, templatesOf(api.detail), api.refreshMinutes, api.liveSeconds),
            )
        }
        val notes = ArrayList<String>()
        val name = obj.optString("name").trim().take(ApiTileJson.MAX_NAME)
        val rawUrl = obj.optString("url").trim()
        val url = if (rawUrl.isEmpty()) "" else ApiStore.address(rawUrl) ?: "".also { notes += "the address isn't an https address, so the tile keeps its own" }
        val headers = headerNames(obj.opt("headers"), notes)
        val tile = view(obj.optJSONObject("tile"), "tile", notes)
        val expanded = obj.optJSONObject("expanded") ?: obj.optJSONObject("detail")
        val detail = view(expanded, "expanded view", notes)
        val rawDetailUrl = expanded?.optString("url")?.trim().orEmpty()
        val detailUrl = if (rawDetailUrl.isEmpty()) {
            ""
        } else {
            ApiAddress.normalize(rawDetailUrl) ?: "".also { notes += "the expanded view's address can't make an https address, so it reads the tile's" }
        }
        val refresh = obj.optInt("refreshMinutes", -1).takeIf { it > 0 }?.let { m ->
            m.coerceIn(ApiTileJson.REFRESH_MINUTES).also { if (it != m) notes += "refreshes every $it min (the closest it can)" }
        }
        val live = if (!obj.has("liveSeconds")) null else obj.optInt("liveSeconds", 0).let { s ->
            if (s <= 0) 0 else s.coerceIn(ApiTileJson.LIVE_SECONDS).also { if (it != s) notes += "live updates every $it s (the closest it can)" }
        }
        if (tile.isEmpty() && detail.isEmpty()) return Parsed.Failed("nothing to show in it: it needs a \"tile\" with fields like \"title\"")
        return Parsed.Ok(Draft(name, url, headers, tile, detailUrl, detail, refresh, live), notes)
    }

    private fun headerNames(value: Any?, notes: MutableList<String>): List<String> {
        val names = when (value) {
            is JSONArray -> (0 until value.length()).mapNotNull { i ->
                when (val h = value.opt(i)) {
                    is String -> h
                    is JSONObject -> h.optString("name").ifEmpty { null }.also { if (h.has("value")) notes += "header values aren't imported: type them in, they stay encrypted on this phone" }
                    else -> null
                }
            }
            is JSONObject -> value.keys().asSequence().toList().also { if (it.isNotEmpty()) notes += "header values aren't imported: type them in, they stay encrypted on this phone" }
            else -> emptyList()
        }
        val good = names.map { it.trim() }.filter { ApiTileJson.validHeader(it) }.distinctBy { it.lowercase() }.take(ApiTileJson.MAX_HEADERS)
        (names.map { it.trim() } - good.toSet()).filter { it.isNotEmpty() }.forEach { notes += "$it can't be used as a header name" }
        return good
    }

    private fun view(obj: JSONObject?, which: String, notes: MutableList<String>): Map<ApiTarget, String> {
        obj ?: return emptyMap()
        val out = LinkedHashMap<ApiTarget, String>()
        for (key in obj.keys().asSequence().toList()) {
            if (key == "url") continue
            val value = obj.opt(key)
            // The list may also come as {"path", "text", "detail", "image"}.
            if (key == "list" && value is JSONObject) {
                value.optString("path").trim().takeIf { it.isNotEmpty() }?.let { out[ApiTarget.LIST] = it.take(200) }
                listOf("text" to ApiTarget.ROW_PRIMARY, "detail" to ApiTarget.ROW_SECONDARY, "image" to ApiTarget.ROW_IMAGE).forEach { (k, t) ->
                    value.optString(k).trim().takeIf { it.isNotEmpty() }?.let { out[t] = it }
                }
                continue
            }
            val target = KEYS[key]
            if (target == null) {
                notes += "$which: \"$key\" isn't a field a tile has, so it was left out"
                continue
            }
            val text = when (value) {
                is String -> value.trim()
                is Number, is Boolean -> value.toString()
                else -> ""
            }
            if (text.isNotEmpty()) out[target] = if (target.isPath) text.take(200) else text
        }
        // Formulas that can't be read, or are too long, never reach the tile.
        for ((target, text) in out.toMap()) {
            if (target.isPath) continue
            val problem = if (text.length > BuiltInFormulas.MAX_TEMPLATE) "too long" else BuiltInFormulas.problem(text)
            if (problem != null) {
                out.remove(target)
                notes += "$which, ${target.label}: $problem"
            }
        }
        return out
    }

    /** What [check] made of a draft: the draft with only what works, and what was left out. */
    data class Checked(val draft: Draft, val notes: List<String>)

    /**
     * [draft] held against the answers: [tileRoot] for the tile, [detailRoot] for an expanded view
     * with its own address (null when it wasn't fetched, so only its formulas are checked). A field
     * the answer doesn't have, a formula that fails, or a list that isn't one is left out, with a
     * note. Never throws.
     */
    fun check(draft: Draft, tileRoot: Any?, detailRoot: Any?, context: FormulaContext): Checked {
        val notes = ArrayList<String>()
        val tile = try {
            checkView(draft.tile, tileRoot, "tile", context, notes)
        } catch (e: Throwable) {
            notes += "tile: couldn't check it, so its fields were left out"
            emptyMap()
        }
        var detailUrl = draft.detailUrl
        if (ApiAddress.usesFields(detailUrl) && tileRoot != null) {
            val r = ApiAddress.resolve(detailUrl, tileRoot, true, context)
            if (r is ApiAddress.Resolved.Problem) {
                notes += "${r.message}, so the expanded view reads the tile's address"
                detailUrl = ""
            }
        }
        val ownAnswer = detailUrl.isNotEmpty()
        val detailAnswer = if (ownAnswer) detailRoot else tileRoot
        val detail = try {
            if (detailAnswer == null) {
                if (draft.detail.isNotEmpty() && ownAnswer) notes += "the expanded view's fields weren't checked: its answer wasn't fetched"
                draft.detail
            } else {
                checkView(draft.detail, detailAnswer, "expanded view", context, notes)
            }
        } catch (e: Throwable) {
            notes += "expanded view: couldn't check it, so its fields were left out"
            emptyMap()
        }
        return Checked(draft.copy(tile = tile, detail = detail, detailUrl = detailUrl), notes)
    }

    private fun checkView(view: Map<ApiTarget, String>, root: Any?, which: String, context: FormulaContext, notes: MutableList<String>): Map<ApiTarget, String> {
        if (view.isEmpty()) return view
        if (root == null) return view
        val out = LinkedHashMap(view)
        // The list: a path to a list in the answer, and a row text read against its first item.
        val listPath = out[ApiTarget.LIST]
        var item: Any? = null
        if (listPath != null) {
            val list = if (listPath == "$" || listPath.isBlank()) root else ApiJson.valueAt(root, listPath)
            if (list !is JSONArray) {
                notes += "$which, list: \"$listPath\" isn't a list in the answer"
                ApiTarget.entries.filter { it.isPath || it.isRow }.forEach { out.remove(it) }
            } else {
                item = list.opt(0)
                if (out[ApiTarget.ROW_PRIMARY].isNullOrBlank()) {
                    val (primary, secondary) = ApiJson.suggestRow(item)
                    primary?.let { out[ApiTarget.ROW_PRIMARY] = if (it == "@") "{@}" else "{$it}" }
                    if (out[ApiTarget.ROW_SECONDARY].isNullOrBlank()) secondary?.let { out[ApiTarget.ROW_SECONDARY] = "{$it}" }
                    if (primary == null) out.remove(ApiTarget.LIST)
                }
            }
        } else {
            ApiTarget.entries.filter { it.isRow }.forEach { t -> if (out.remove(t) != null) notes += "$which, ${t.label}: there's no list for rows" }
        }
        for ((target, text) in out.toMap()) {
            if (target.isPath) continue
            val row = if (target.isRow) (item ?: return@checkView out) else io.github.danielphingston.metro.live.api.ApiFormulas.NO_ROW
            val missing = BuiltInFormulas.missingPaths(text, root, context, row)
            val e = BuiltInFormulas.evaluate(text, root, context, row)
            when {
                missing == null -> {
                    out.remove(target)
                    notes += "$which, ${target.label}: ${BuiltInFormulas.problem(text) ?: "can't read that formula"}"
                }
                missing.isNotEmpty() -> {
                    out.remove(target)
                    notes += "$which, ${target.label}: the answer has no ${missing.take(3).joinToString(", ")}"
                }
                e.error != null -> {
                    out.remove(target)
                    notes += "$which, ${target.label}: ${e.error}"
                }
            }
        }
        if (out[ApiTarget.LIST] != null && out[ApiTarget.ROW_PRIMARY].isNullOrBlank()) {
            out.remove(ApiTarget.LIST)
            ApiTarget.entries.filter { it.isRow }.forEach { out.remove(it) }
            notes += "$which, list: no row text works with its items, so it was left out"
        }
        return out
    }

    /**
     * The setup as the import reads it, for sharing a tile to another phone or chat: no header
     * values, ever.
     */
    fun export(draft: Draft): String {
        fun view(url: String, t: Map<ApiTarget, String>) = JSONObject().apply {
            if (url.isNotEmpty()) put("url", url)
            KEYS.forEach { (k, target) -> t[target]?.takeIf { it.isNotBlank() }?.let { put(k, it) } }
        }
        return JSONObject()
            .put("name", draft.name)
            .put("url", draft.url)
            .apply { if (draft.headers.isNotEmpty()) put("headers", JSONArray(draft.headers)) }
            .put("tile", view("", draft.tile))
            .apply { if (draft.detail.isNotEmpty() || draft.detailUrl.isNotEmpty()) put("expanded", view(draft.detailUrl, draft.detail)) }
            .apply { draft.refreshMinutes?.let { put("refreshMinutes", it) } }
            .toString(2)
    }

    /** How to write a tile's setup: for a chatbot ([prompt]) and the on-device AI ([TileAi]). */
    val FORMAT: String = """
The tile setup is one JSON object:
{
  "name": "short name shown on the tile",
  "url": "https://the.api/address (the API that answers with JSON)",
  "headers": ["X-Api-Key"],
  "tile": {
    "title": "{name}",
    "value": "{round(main.temp, 1)}°",
    "line1": "text and {fields}",
    "line2": "text and {fields}",
    "image": "{field holding an https picture address}",
    "link": "{field holding an https page address}",
    "list": "path.to.a.list",
    "rowText": "{field of each item}",
    "rowDetail": "{another field of each item}",
    "rowImage": "{picture field of each item}"
  },
  "expanded": {
    "url": "optional: another address, can use the tile's fields like {species.url} or https://the.api/items/{id}",
    "title": "...", "value": "...", "line1": "...", "line2": "...", "list": "...", "rowText": "...", "rowDetail": "..."
  },
  "refreshMinutes": 15
}
Rules:
- Every field is optional except tile with at least one of title, value, line1, line2 or list. Leave out what isn't needed.
- "headers" lists header NAMES only (like an API key header). Never put a key or any secret in the JSON.
- Text in {braces} is a formula reading the API's JSON answer: a path like {name}, {main.temp}, {items[0].title}, {sprites.other['official-artwork'].front_default}. Text outside braces is shown as is.
- "list" is a plain path to an array (no braces), or "$" when the whole answer is an array. rowText, rowDetail and rowImage read each item first: {name}, {stat.name}; {@} is the item itself; {$.path} reads the whole answer.
- Formulas can use: + - * / %, comparisons, a ?? 'fallback' for missing values, and functions:
  round(x, 1), fixed(x, 2), compact(1234567) gives 1.2M, thousands(x), upper(x), lower(x), title(x),
  count(list), join(list, ', ', 'field'), sum(list, 'field'), first(list), last(list), if(cond, 'a', 'b'),
  convert(x, 'c', 'f') for units, time(t), date(t), countdown(t) for "2h 5m" until a time, until(t) in seconds, ago(t) for "5 min ago".
  Times can be ISO 8601 text or seconds or milliseconds since 1970.
- "expanded" is the view a tap on the tile opens; leave out "url" to read the tile's own address.
- Use only fields that are really in the API's answer.
""".trim()

    /**
     * What to give a chatbot so it writes a tile's setup: the format, the API's address and the
     * fields of its answer when they're known, and what the person wants. Only the JSON comes back.
     */
    fun prompt(url: String, want: String, root: Any?): String = buildString {
        appendLine("Write the setup for a custom tile in Metro launcher, an Android home screen app. The tile shows live data from a JSON API.")
        appendLine("Reply with only the JSON object, nothing before or after it, no code fences.")
        appendLine()
        appendLine(FORMAT)
        appendLine()
        appendLine("The API: ${url.ifBlank { "<paste the API's https address here>" }}")
        val fields = fieldSummary(root, maxFields = 150)
        if (fields.isNotEmpty()) {
            appendLine("Its answer has these fields (path = sample):")
            appendLine(fields)
        } else {
            appendLine("If you can't open the address, ask me to paste a sample of its JSON answer.")
        }
        appendLine()
        append("What I want the tile to show: ${want.ifBlank { "<describe the tile here>" }}")
    }

    /** How deep a field sits in the answer: top-level fields come first when the list of fields is cut. */
    fun depth(path: String): Int = path.count { it == '.' || it == '[' }

    /** The answer's fields as "path = sample" lines, at most [maxFields]: what a model is told about the answer. */
    fun fieldSummary(root: Any?, maxFields: Int): String = try {
        if (root == null) "" else ApiJson.fields(root).sortedBy { depth(it.path) }.take(maxFields).joinToString("\n") { f ->
            if (f.list) "${f.path} = list of ${f.count}" else "${f.path} = ${f.sample}"
        }
    } catch (e: Throwable) {
        ""
    }
}
