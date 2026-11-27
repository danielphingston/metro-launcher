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
        /** The face the tile flips to; a blank [backUrl] reads the tile's answer. */
        val backUrl: String = "",
        val back: Map<ApiTarget, String> = emptyMap(),
    )

    sealed interface Parsed {
        /** Read: [notes] lists everything that was skipped and why. */
        data class Ok(val draft: Draft, val notes: List<String> = emptyList()) : Parsed
        /** Nothing usable: [message] says why, and [errors] lists everything that was skipped. */
        data class Failed(val message: String, val errors: List<String> = emptyList()) : Parsed
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
                Draft(api.name, api.tile.url, api.headers, templatesOf(api.tile), api.detail.url, templatesOf(api.detail), api.refreshMinutes, api.liveSeconds, api.back.url, templatesOf(api.back)),
            )
        }
        // Strict: only the keys and types of the format below. Anything else is skipped and listed.
        val errors = ArrayList<String>()
        obj.keys().asSequence().toList().filter { it !in TOP_KEYS }.forEach { errors += "\"$it\" isn't part of a tile setup: skipped" }
        val name = when (val v = obj.opt("name")) {
            null -> ""
            is String -> v.trim().let { n ->
                if (n.length > ApiTileJson.MAX_NAME) "".also { errors += "name: longer than ${ApiTileJson.MAX_NAME} characters: skipped" } else n
            }
            else -> "".also { errors += "name: must be text: skipped" }
        }
        val url = when (val v = obj.opt("url")) {
            null -> ""
            is String -> if (v.isBlank()) "" else ApiAddress.tileAddress(v.trim()) ?: "".also { errors += "url: ${(ApiAddress.tileProblem(v) ?: "must be an https address").removePrefix("the tile's address ").removePrefix("the tile's address: ")}: skipped, the tile keeps its own" }
            else -> "".also { errors += "url: must be text: skipped, the tile keeps its own" }
        }
        val headers = headerNames(obj.opt("headers"), errors)
        val tile = viewOf(obj, "tile", errors)
        if (obj.has("expanded") && obj.has("detail")) errors += "\"detail\" and \"expanded\" are the same view: \"detail\" skipped"
        val expandedKey = if (obj.has("expanded")) "expanded" else "detail"
        val detail = viewOf(obj, expandedKey, errors)
        val detailUrl = when (val v = (obj.opt(expandedKey) as? JSONObject)?.opt("url")) {
            null -> ""
            is String -> if (v.isBlank()) "" else ApiAddress.normalize(v) ?: "".also { errors += "$expandedKey.url: must make an https address: skipped, the expanded view reads the tile's" }
            else -> "".also { errors += "$expandedKey.url: must be text: skipped" }
        }
        val back = viewOf(obj, "back", errors)
        val backUrl = when (val v = (obj.opt("back") as? JSONObject)?.opt("url")) {
            null -> ""
            is String -> if (v.isBlank()) "" else ApiAddress.normalize(v) ?: "".also { errors += "back.url: must make an https address: skipped, the back reads the tile's" }
            else -> "".also { errors += "back.url: must be text: skipped" }
        }
        val refresh = whole(obj, "refreshMinutes", errors)?.let { m ->
            if (m !in ApiTileJson.REFRESH_MINUTES) null.also { errors += "refreshMinutes: must be ${ApiTileJson.REFRESH_MINUTES.first} to ${ApiTileJson.REFRESH_MINUTES.last}: skipped" } else m
        }
        val live = whole(obj, "liveSeconds", errors)?.let { s ->
            when {
                s == 0 -> 0
                s in ApiTileJson.LIVE_SECONDS -> s
                else -> null.also { errors += "liveSeconds: must be 0 (off) or ${ApiTileJson.LIVE_SECONDS.first} to ${ApiTileJson.LIVE_SECONDS.last}: skipped" }
            }
        }
        if (tile.isEmpty() && detail.isEmpty() && back.isEmpty()) {
            return Parsed.Failed("nothing usable in it: it needs a \"tile\" with fields like \"title\"", errors)
        }
        return Parsed.Ok(Draft(name, url, headers, tile, detailUrl, detail, refresh, live, backUrl, back), errors)
    }

    private val TOP_KEYS = setOf("name", "url", "headers", "tile", "back", "expanded", "detail", "refreshMinutes", "liveSeconds")
    private val LIST_KEYS = mapOf("path" to ApiTarget.LIST, "text" to ApiTarget.ROW_PRIMARY, "detail" to ApiTarget.ROW_SECONDARY, "image" to ApiTarget.ROW_IMAGE)

    /** A whole number at [key], or null (with an error when it is there but isn't one). */
    private fun whole(obj: JSONObject, key: String, errors: MutableList<String>): Int? {
        val v = obj.opt(key) ?: return null
        val n = (v as? Number)?.toDouble()
        if (n == null || n != Math.floor(n) || n.isInfinite() || n > Int.MAX_VALUE || n < Int.MIN_VALUE) {
            errors += "$key: must be a whole number: skipped"
            return null
        }
        return n.toInt()
    }

    private fun headerNames(value: Any?, errors: MutableList<String>): List<String> {
        val names = ArrayList<String>()
        var values = false
        when (value) {
            null -> return emptyList()
            is JSONArray -> for (i in 0 until value.length()) {
                when (val h = value.opt(i)) {
                    is String -> names += h
                    is JSONObject -> {
                        if (h.has("value")) values = true
                        h.keys().asSequence().filter { it != "name" && it != "value" }.forEach { errors += "headers[$i].$it: isn't part of a header: skipped" }
                        (h.opt("name") as? String)?.let { names += it } ?: run { errors += "headers[$i]: needs a \"name\": skipped" }
                    }
                    else -> errors += "headers[$i]: must be a header name: skipped"
                }
            }
            is JSONObject -> {
                names += value.keys().asSequence().toList()
                if (names.isNotEmpty()) values = true
            }
            else -> {
                errors += "headers: must be a list of header names: skipped"
                return emptyList()
            }
        }
        if (values) errors += "headers: values aren't imported: type them in, they stay encrypted on this phone"
        val out = ArrayList<String>()
        for (raw in names) {
            val h = raw.trim()
            when {
                !ApiTileJson.validHeader(h) -> errors += "headers: \"$h\" can't be used as a header name: skipped"
                out.any { it.equals(h, ignoreCase = true) } -> errors += "headers: \"$h\" is there twice: skipped"
                out.size >= ApiTileJson.MAX_HEADERS -> errors += "headers: at most ${ApiTileJson.MAX_HEADERS}: \"$h\" skipped"
                else -> out += h
            }
        }
        return out
    }

    /** The view at [key] of [parent]: only known keys, each text, each formula readable; the rest skipped with an error. */
    private fun viewOf(parent: JSONObject, key: String, errors: MutableList<String>): Map<ApiTarget, String> {
        val obj = when (val v = parent.opt(key)) {
            null -> return emptyMap()
            is JSONObject -> v
            else -> return emptyMap<ApiTarget, String>().also { errors += "$key: must be an object of fields: skipped" }
        }
        val out = LinkedHashMap<ApiTarget, String>()
        fun put(where: String, target: ApiTarget, value: Any?) {
            when {
                value !is String -> errors += "$where: must be text: skipped"
                value.isBlank() -> Unit
                target.isPath && value.contains('{') -> errors += "$where: must be a plain path like stats, without braces: skipped"
                target.isPath && value.trim().length > 200 -> errors += "$where: longer than 200 characters: skipped"
                !target.isPath && value.length > BuiltInFormulas.MAX_TEMPLATE -> errors += "$where: longer than ${BuiltInFormulas.MAX_TEMPLATE} characters: skipped"
                !target.isPath && BuiltInFormulas.problem(value) != null -> errors += "$where: ${BuiltInFormulas.problem(value)}: skipped"
                else -> out[target] = value.trim()
            }
        }
        for (k in obj.keys().asSequence().toList()) {
            val value = obj.opt(k)
            when {
                k == "url" -> if (key == "tile") errors += "tile.url: the address goes at the top, as \"url\": skipped"
                k == "link" && key == "back" -> errors += "back.link: the back has no link of its own, a tap does what the front does: skipped"
                k == "list" && value is JSONObject -> {
                    for (lk in value.keys().asSequence().toList()) {
                        val target = LIST_KEYS[lk]
                        if (target == null) errors += "$key.list.$lk: isn't part of a list (path, text, detail, image): skipped" else put("$key.list.$lk", target, value.opt(lk))
                    }
                }
                KEYS[k] != null -> put("$key.$k", KEYS[k]!!, value)
                else -> errors += "$key.$k: isn't a field a tile has: skipped"
            }
        }
        if (ApiTarget.LIST !in out) {
            ApiTarget.entries.filter { it.isRow }.forEach { t -> if (out.remove(t) != null) errors += "$key.${KEYS.entries.first { it.value == t }.key}: rows need a \"list\": skipped" }
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
    fun check(draft: Draft, tileRoot: Any?, detailRoot: Any?, context: FormulaContext, backRoot: Any? = null): Checked {
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
        var backUrl = draft.backUrl
        if (ApiAddress.usesFields(backUrl) && tileRoot != null) {
            val r = ApiAddress.resolve(backUrl, tileRoot, true, context)
            if (r is ApiAddress.Resolved.Problem) {
                notes += "back: ${r.message.removePrefix("the expanded view's address: ")}, so the back reads the tile's address"
                backUrl = ""
            }
        }
        val backAnswer = if (backUrl.isNotEmpty()) backRoot else tileRoot
        val back = try {
            if (backAnswer == null) {
                if (draft.back.isNotEmpty() && backUrl.isNotEmpty()) notes += "the back's fields weren't checked: its answer wasn't fetched"
                draft.back
            } else {
                checkView(draft.back, backAnswer, "back", context, notes)
            }
        } catch (e: Throwable) {
            notes += "back: couldn't check it, so its fields were left out"
            emptyMap()
        }
        return Checked(draft.copy(tile = tile, detail = detail, detailUrl = detailUrl, back = back, backUrl = backUrl), notes)
    }

    /** Where a field sits in the import format, as errors name it: "tile.value", "expanded.list". */
    private fun at(which: String, target: ApiTarget): String =
        (when (which) { "tile", "back" -> which; else -> "expanded" }) + "." + (KEYS.entries.firstOrNull { it.value == target }?.key ?: target.label)

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
                notes += "${at(which, ApiTarget.LIST)}: \"$listPath\" isn't a list in the answer: skipped with its rows"
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
            ApiTarget.entries.filter { it.isRow }.forEach { t -> if (out.remove(t) != null) notes += "${at(which, t)}: rows need a \"list\": skipped" }
        }
        for ((target, text) in out.toMap()) {
            if (target.isPath) continue
            val row = if (target.isRow) (item ?: return@checkView out) else io.github.danielphingston.metro.live.api.ApiFormulas.NO_ROW
            val missing = BuiltInFormulas.missingPaths(text, root, context, row)
            val e = BuiltInFormulas.evaluate(text, root, context, row)
            when {
                missing == null -> {
                    out.remove(target)
                    notes += "${at(which, target)}: ${BuiltInFormulas.problem(text) ?: "can't read that formula"}: skipped"
                }
                missing.isNotEmpty() -> {
                    out.remove(target)
                    notes += "${at(which, target)}: the answer has no ${missing.take(3).joinToString(", ")}: skipped"
                }
                e.error != null -> {
                    out.remove(target)
                    notes += "${at(which, target)}: ${e.error}: skipped"
                }
            }
        }
        if (out[ApiTarget.LIST] != null && out[ApiTarget.ROW_PRIMARY].isNullOrBlank()) {
            out.remove(ApiTarget.LIST)
            ApiTarget.entries.filter { it.isRow }.forEach { out.remove(it) }
            notes += "${at(which, ApiTarget.LIST)}: no row text works with its items: skipped"
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
            .apply { if (draft.back.isNotEmpty() || draft.backUrl.isNotEmpty()) put("back", view(draft.backUrl, draft.back)) }
            .apply { if (draft.detail.isNotEmpty() || draft.detailUrl.isNotEmpty()) put("expanded", view(draft.detailUrl, draft.detail)) }
            .apply { draft.refreshMinutes?.let { put("refreshMinutes", it) } }
            .toString(2)
    }

    /** How to write a tile's setup: for a chatbot ([prompt]) and the on-device AI ([TileAi]). */
    val FORMAT: String by lazy {
        """
The tile setup is one JSON object:
{
  "name": "short name shown on the tile",
  "url": "https://the.api/address (the API that answers with JSON; can hold formulas after the site, like https://the.api/items/{random(1, 100)})",
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
  "back": {
    "url": "optional: another address, like the expanded view's",
    "title": "...", "value": "...", "line1": "...", "line2": "...", "image": "...", "list": "...", "rowText": "...", "rowDetail": "..."
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
- Formulas can use numbers, 'text' or "text", + (adds numbers or joins text) - * / %, comparisons == != < > <= >= (true or false), ( ), and a ?? 'fallback' when the left side is missing. There is no && or ||; nest if() instead.
- These are ALL the functions; nothing else exists (no toFixed, no format, no Math.x, no methods on values):
${'$'}FUNCTION_LIST
  Times can be ISO 8601 text or seconds or milliseconds since 1970.
  Units for convert: temperature c f k; length m km cm mm mi ft in yd; mass kg g lb oz t; speed m/s km/h mph kn; volume l ml gal; data b kb mb gb tb kib mib gib; pressure hpa mbar psi inhg.
- The tile's own "url" can hold formulas after its site, worked out afresh on every refresh. They can use functions and the clock but no fields, since there's no answer yet when it's fetched, and the site itself stays fixed (formulas go after the first /).
  A new Pokémon every refresh: "url": "https://pokeapi.co/api/v2/pokemon/{random(1, 1025)}" (1025 is the National Dex count; higher ids are alternate forms).
  A Pokémon of the day, the same one all day: "url": "https://pokeapi.co/api/v2/pokemon/{dayOfYear() * 7 % 1025 + 1}".
- "back" is optional: the face the tile flips to now and then on the home screen, like a Windows Phone live tile. It has the same fields as "tile" except link (a tap does what the front does). Without its own "url" it reads the tile's answer; its "url" works like the expanded view's below. Use it for a second look at the data, like the stats behind a headline number. Leave it out for a tile that shouldn't flip.
- "expanded" is the view a tap on the tile opens. Without its own "url" it reads the tile's answer.
- The expanded view can fetch other data: its "url" can take a link from the tile's answer, either a whole field ("{species.url}") or fields inside an address ("https://the.api/items/{id}"). It is worked out again each time the tile's answer changes, and the expanded view's fields then read that second answer, not the tile's.
  Example: the tile reads https://pokeapi.co/api/v2/pokemon/pikachu, whose answer has species.url; "expanded": {"url": "{species.url}", "title": "{name}", "line1": "colour {color.name}"} shows the species data.
- Headers go only to the tile's own site: a link from the answer that points to another site is fetched without them.
- If you don't know the second address's answer, keep the expanded fields to likely ones; anything that isn't in it is skipped when it's checked.
- Use only the keys shown here, with text values. refreshMinutes is a whole number from 5 to 60; liveSeconds is 0 (off) or 5 to 120. Anything else is skipped as an error.
- Use only fields that are really in the API's answer.
""".trim().replace("${'$'}FUNCTION_LIST", functionList())
    }

    /** What each function does, by name; [functionList] lists them in the formula engine's own order, so none is ever missing. */
    private val FUNCTION_HELP = mapOf(
        "upper" to "text in capitals",
        "lower" to "text in small letters",
        "title" to "Each Word Capitalised",
        "trim" to "text without spaces at the ends",
        "left" to "the first n characters",
        "right" to "the last n characters",
        "replace" to "every 'from' replaced with 'to'",
        "len" to "how many characters, or items in a list",
        "round" to "rounded to n decimals (0 if left out)",
        "floor" to "rounded down",
        "ceil" to "rounded up",
        "abs" to "without its minus sign",
        "min" to "the smallest of up to 8 numbers",
        "max" to "the largest of up to 8 numbers",
        "fixed" to "always n decimals: fixed(3.1, 2) gives 3.10",
        "thousands" to "with thousands separators: 1,234,567",
        "compact" to "short: 1234567 gives 1.2M",
        "percent" to "a share as a percentage: percent(0.25) gives 25%",
        "convert" to "converts between units (listed below)",
        "count" to "how many items a list has",
        "first" to "a list's first item",
        "last" to "a list's last item",
        "at" to "the item at an index, counting from 0",
        "join" to "a list's items (or one field of each) joined with a separator",
        "sum" to "the total of a list's numbers, or of one field of each item",
        "avg" to "the average of a list's numbers, or of one field of each item",
        "countdown" to "time left until a time: 2d 4h, 4h 12m, 12:04; ticks on screen",
        "until" to "seconds until a time",
        "ago" to "how long ago a time was: now, 5m, 3h, 2d",
        "time" to "a time's clock time; optional time zone like 'Europe/London'",
        "date" to "a time's date; optional pattern like 'EEE d MMM' or 'd/M/yyyy' and time zone",
        "datetime" to "reads text as a time, like datetime(day + 'T' + hour)",
        "random" to "a whole number from min to max, both included; picked again on every refresh",
        "dayOfYear" to "today's day of the year, 1 to 366, in the phone's time zone (or of a time given to it); changes at midnight",
        "if" to "the second value when the test is true, else the third (or nothing)",
        "default" to "the second value when the first is missing or empty",
        "number" to "text read as a number",
        "text" to "a value as text",
    )

    private fun functionList(): String = BuiltInFormulas.FUNCTIONS.joinToString("\n") { (name, example) ->
        "  $example: ${FUNCTION_HELP[name] ?: name}"
    }

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
