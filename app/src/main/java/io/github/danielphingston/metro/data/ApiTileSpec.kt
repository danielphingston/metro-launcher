package io.github.danielphingston.metro.data

import io.github.danielphingston.metro.live.api.ApiAddress
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import org.json.JSONArray
import org.json.JSONObject

/** Where on an API tile (or its expanded view) a template's text goes. [id] is saved in the layout. */
enum class ApiSlot(val id: String, val label: String) {
    TITLE("title", "title"),
    VALUE("value", "big value"),
    LINE1("line1", "line 1"),
    LINE2("line2", "line 2"),
    IMAGE("image", "picture"),
    LINK("link", "link"),
    ;

    companion object {
        fun fromId(id: String): ApiSlot? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A list in the JSON shown as repeating rows: the array at [path] ("$" when the document is itself
 * a list), each item shown with [primary], [secondary] and an [image] address, templates read
 * against the item first (see [BuiltInFormulas]).
 */
data class ApiList(val path: String, val primary: String, val secondary: String = "", val image: String = "")

/**
 * One view of an API tile: the address it reads ([url]; on the expanded view, blank means the
 * tile's own) and a template for each [ApiSlot]. On the expanded view an empty slot shows the
 * tile's template for it.
 */
data class ApiView(
    val url: String = "",
    val slots: Map<ApiSlot, String> = emptyMap(),
    val list: ApiList? = null,
) {
    fun slot(s: ApiSlot): String = slots[s].orEmpty()
}

/** Reading and writing API tiles in the tiles JSON (type "api"). Older versions keep the entry as unread and write it back as it was. */
internal object ApiTileJson {
    const val TYPE = "api"
    const val MAX_NAME = 40
    const val MAX_HEADERS = 6
    val REFRESH_MINUTES = 5..60
    val LIVE_SECONDS = 5..120

    /** Header names a tile may set: letters, digits and dashes, and none the download sets itself. */
    private val headerName = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
    private val reserved = setOf(
        "host", "connection", "content-length", "transfer-encoding", "user-agent", "accept-encoding",
        "if-none-match", "if-modified-since", "upgrade", "te", "trailer", "proxy-authorization", "keep-alive",
    )

    fun validHeader(name: String): Boolean = headerName.matches(name) && name.lowercase() !in reserved

    fun encode(c: TileContent.Api, obj: JSONObject): JSONObject = obj
        .put("type", TYPE)
        .put("name", c.name)
        .put("tile", view(c.tile))
        .put("detail", view(c.detail))
        .put("headers", JSONArray(c.headers))
        .put("refreshMinutes", c.refreshMinutes)
        .put("liveSeconds", c.liveSeconds)
        .put("wifiOnly", c.wifiOnly)
        .put("created", c.created)

    private fun view(v: ApiView): JSONObject = JSONObject().apply {
        if (v.url.isNotEmpty()) put("url", v.url)
        val slots = JSONObject()
        v.slots.forEach { (k, t) -> if (t.isNotEmpty()) slots.put(k.id, t) }
        put("slots", slots)
        v.list?.let { put("list", JSONObject().put("path", it.path).put("primary", it.primary).put("secondary", it.secondary).put("image", it.image)) }
    }

    /**
     * An API tile from the layout, or null when it can't be one. A restored backup is a file from
     * anywhere, so everything is checked again: addresses must be https, templates are cut to
     * [BuiltInFormulas.MAX_TEMPLATE], numbers clamped, unknown header names dropped.
     */
    fun decode(obj: JSONObject): TileContent.Api? {
        val tile = readView(obj.optJSONObject("tile")) ?: return null
        if (tile.url.isEmpty()) return null
        // The expanded view's address may take values from the tile's answer ({species.url}).
        val detail = readView(obj.optJSONObject("detail"), fields = true) ?: ApiView()
        val headers = obj.optJSONArray("headers")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(::validHeader) } }
            .orEmpty().distinctBy { it.lowercase() }.take(MAX_HEADERS)
        val live = obj.optInt("liveSeconds", 0)
        return TileContent.Api(
            name = obj.optString("name").take(MAX_NAME),
            tile = tile,
            detail = detail,
            headers = headers,
            refreshMinutes = obj.optInt("refreshMinutes", 15).coerceIn(REFRESH_MINUTES),
            liveSeconds = if (live <= 0) 0 else live.coerceIn(LIVE_SECONDS),
            wifiOnly = obj.optBoolean("wifiOnly", false),
            created = obj.optLong("created", 0),
        )
    }

    private fun readView(obj: JSONObject?, fields: Boolean = false): ApiView? {
        obj ?: return null
        val rawUrl = obj.optString("url")
        val url = when {
            rawUrl.isEmpty() -> ""
            fields -> ApiAddress.normalize(rawUrl) ?: return null
            else -> ApiStore.address(rawUrl) ?: return null
        }
        val slots = obj.optJSONObject("slots")?.let { s ->
            s.keys().asSequence().mapNotNull { k -> ApiSlot.fromId(k)?.let { it to template(s.optString(k)) } }.filter { it.second.isNotEmpty() }.toMap()
        }.orEmpty()
        val list = obj.optJSONObject("list")?.let { l ->
            val path = l.optString("path").take(200).ifEmpty { return@let null }
            ApiList(path, template(l.optString("primary")), template(l.optString("secondary")), template(l.optString("image")))
        }
        return ApiView(url, slots, list)
    }

    private fun template(text: String): String = text.take(BuiltInFormulas.MAX_TEMPLATE)
}
