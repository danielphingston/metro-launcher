package io.github.danielphingston.metro.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class TileSize(val width: Int, val height: Int) {
    SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2), LARGE(4, 4);

    /** Windows Phone's resize button order. */
    fun next(): TileSize = when (this) {
        MEDIUM -> SMALL
        SMALL -> WIDE
        WIDE -> LARGE
        LARGE -> MEDIUM
    }
}

/** What a tile shows and does. */
sealed interface TileContent {
    /** An app's main activity; may carry live content (notifications, clock, calendar, media). */
    data class App(val app: AppKey) : TileContent

    /** A pinned app shortcut (Android's equivalent of a Windows Phone secondary tile). */
    data class Shortcut(val packageName: String, val shortcutId: String, val userSerial: Long, val label: String) :
        TileContent

    /** A hosted Android app widget. [provider] is a flattened ComponentName. */
    data class Widget(val appWidgetId: Int, val provider: String, val userSerial: Long) : TileContent

    data object Weather : TileContent
    data object Photos : TileContent
    data object People : TileContent

    /** Windows Phone 8.1 tile folder. Children are app or shortcut tiles. */
    data class Folder(val name: String, val children: List<TileSpec>) : TileContent
}

data class TileSpec(val id: Long, val size: TileSize, val content: TileContent) {
    /** The app key for app tiles, else null. */
    val appKey: AppKey? get() = (content as? TileContent.App)?.app
}

/**
 * The ordered list of pinned tiles. Order is what the user arranges; the grid position is
 * derived from it by [io.github.danielphingston.metro.ui.start.TileGrid.pack].
 */
class TileRepository(private val context: Context, scope: CoroutineScope) {
    private val key = stringPreferencesKey("tiles")

    /** Null until loaded. [isFirstRun] tells whether a layout has ever been saved. */
    private val _tiles = MutableStateFlow<List<TileSpec>?>(null)
    val tiles: StateFlow<List<TileSpec>?> = _tiles.asStateFlow()

    var isFirstRun = false
        private set

    init {
        scope.launch {
            val stored = context.metroDataStore.data.first()[key]
            isFirstRun = stored == null
            _tiles.value = stored?.let(::decode).orEmpty()
            persistChanges()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persistChanges() {
        _tiles.filterNotNull().drop(1).debounce(400).collect { list ->
            context.metroDataStore.edit { it[key] = encode(list) }
        }
    }

    fun replaceAll(tiles: List<TileSpec>) {
        isFirstRun = false
        _tiles.value = tiles
    }

    fun pin(app: AppKey, size: TileSize = TileSize.MEDIUM) = add(TileContent.App(app), size)

    /** Appends a tile unless an identical one (same app or shortcut) is already pinned. */
    fun add(content: TileContent, size: TileSize): Long? {
        var newId: Long? = null
        _tiles.update { current ->
            val list = current.orEmpty()
            if (content !is TileContent.Widget && list.flatten().any { it.content == content }) return@update list
            val id = nextId(list)
            newId = id
            list + TileSpec(id, size, content)
        }
        return newId
    }

    fun isPinned(content: TileContent): Boolean = _tiles.value.orEmpty().flatten().any { it.content == content }

    /** Top-level tiles plus the children of folders. */
    fun List<TileSpec>.flatten(): List<TileSpec> =
        flatMap { spec -> listOf(spec) + ((spec.content as? TileContent.Folder)?.children.orEmpty()) }

    fun nextId(list: List<TileSpec>): Long = (list.flatten().maxOfOrNull { it.id } ?: 0) + 1

    /** Replaces the whole list through [transform]; for features that need multi-step edits (folders). */
    fun edit(transform: (List<TileSpec>) -> List<TileSpec>) = _tiles.update { it?.let(transform) }

    /** Removes a tile and returns it, so callers can release resources (e.g. widget ids). */
    fun unpin(id: Long): TileSpec? {
        var removed: TileSpec? = null
        _tiles.update { list ->
            removed = list?.firstOrNull { it.id == id }
            list?.filterNot { tile -> tile.id == id }
        }
        return removed
    }

    fun resize(id: Long) = _tiles.update { list ->
        list?.map { if (it.id == id) it.copy(size = it.size.next()) else it }
    }

    /** Moves tile [id] into the slot of [targetId]; the target and the tiles between shift by one. */
    fun move(id: Long, targetId: Long) = _tiles.update { list ->
        if (list == null) return@update null
        val from = list.indexOfFirst { it.id == id }
        val to = list.indexOfFirst { it.id == targetId }
        if (from < 0 || to < 0 || from == to) return@update list
        list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun removePackage(packageName: String, userSerial: Long) = _tiles.update { list ->
        fun TileSpec.belongsToPackage(): Boolean = when (val c = content) {
            is TileContent.App -> c.app.packageName == packageName && c.app.userSerial == userSerial
            is TileContent.Shortcut -> c.packageName == packageName && c.userSerial == userSerial
            else -> false
        }
        list?.filterNot { it.belongsToPackage() }?.map { spec ->
            val folder = spec.content as? TileContent.Folder ?: return@map spec
            spec.copy(content = folder.copy(children = folder.children.filterNot { it.belongsToPackage() }))
        }
    }

    private fun encode(tiles: List<TileSpec>): String = TileJson.encode(tiles).toString()

    private fun decode(json: String): List<TileSpec> = runCatching { TileJson.decode(JSONArray(json)) }
        .getOrDefault(emptyList())
}

/** JSON form of the start screen. Entries without "type" are app tiles from version 0.1. */
internal object TileJson {
    fun encode(tiles: List<TileSpec>): JSONArray = JSONArray().apply { tiles.forEach { put(encode(it)) } }

    private fun encode(tile: TileSpec): JSONObject {
        val obj = JSONObject().put("id", tile.id).put("size", tile.size.name)
        when (val c = tile.content) {
            is TileContent.App -> obj.put("type", "app").put("app", c.app.serialize())
            is TileContent.Shortcut -> obj.put("type", "shortcut").put("package", c.packageName)
                .put("shortcut", c.shortcutId).put("serial", c.userSerial).put("label", c.label)
            is TileContent.Widget -> obj.put("type", "widget").put("widgetId", c.appWidgetId)
                .put("provider", c.provider).put("serial", c.userSerial)
            TileContent.Weather -> obj.put("type", "weather")
            TileContent.Photos -> obj.put("type", "photos")
            TileContent.People -> obj.put("type", "people")
            is TileContent.Folder -> obj.put("type", "folder").put("name", c.name).put("children", encode(c.children))
        }
        return obj
    }

    fun decode(array: JSONArray): List<TileSpec> = (0 until array.length()).mapNotNull { i ->
        runCatching { decode(array.getJSONObject(i)) }.getOrNull()
    }

    private fun decode(obj: JSONObject): TileSpec? {
        val size = runCatching { TileSize.valueOf(obj.getString("size")) }.getOrDefault(TileSize.MEDIUM)
        val content: TileContent = when (obj.optString("type", "app")) {
            "app" -> TileContent.App(AppKey.parse(obj.getString("app")) ?: return null)
            "shortcut" -> TileContent.Shortcut(
                obj.getString("package"), obj.getString("shortcut"), obj.getLong("serial"), obj.optString("label"),
            )
            "widget" -> TileContent.Widget(obj.getInt("widgetId"), obj.getString("provider"), obj.getLong("serial"))
            "weather" -> TileContent.Weather
            "photos" -> TileContent.Photos
            "people" -> TileContent.People
            "folder" -> TileContent.Folder(obj.optString("name"), decode(obj.getJSONArray("children")))
            else -> return null
        }
        return TileSpec(obj.getLong("id"), size, content)
    }
}
