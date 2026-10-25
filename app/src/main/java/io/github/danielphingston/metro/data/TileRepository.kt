package io.github.danielphingston.metro.data

import android.content.ComponentName
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.ui.start.GridPos
import io.github.danielphingston.metro.ui.start.TilePlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
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

/** A custom footprint in small-tile cells that replaces a tile's [TileSize]; see [TileSpec.span]. */
data class TileSpan(val width: Int, val height: Int) {
    companion object {
        const val MAX_HEIGHT = 8
    }
}

/**
 * A pinned tile. Top-level tiles carry their grid position [x], [y] (top-left, in small-tile
 * cells); [UNPLACED] means "not placed yet", and [TilePlacement] puts such a tile at the bottom
 * of the layout. Folder children have no position: they stay list-ordered inside the folder.
 */
data class TileSpec(
    val id: Long,
    val size: TileSize,
    val content: TileContent,
    val x: Int = UNPLACED,
    val y: Int = UNPLACED,
    /** When set, replaces [size] with any footprint (width 1..columns, height 1..[TileSpan.MAX_HEIGHT]). */
    val span: TileSpan? = null,
) {
    /** The app key for app tiles, else null. */
    val appKey: AppKey? get() = (content as? TileContent.App)?.app

    /** Footprint in cells, before clamping to the column count. */
    val width: Int get() = span?.width ?: size.width
    val height: Int get() = span?.height ?: size.height

    val isPlaced: Boolean get() = x >= 0 && y >= 0

    companion object {
        const val UNPLACED = -1
    }
}

/**
 * The pinned tiles. Each top-level tile owns a grid position and [TilePlacement] settles the
 * layout (upward gravity only) after every change, using the current column count from
 * [columns]; the first value is read before the saved layout is migrated.
 */
class TileRepository(private val context: Context, scope: CoroutineScope, private val columns: Flow<Int>) {
    private val key = stringPreferencesKey("tiles")

    @Volatile
    private var columnCount = 4

    /** Null until loaded. [isFirstRun] tells whether a layout has ever been saved. */
    private val _tiles = MutableStateFlow<List<TileSpec>?>(null)
    val tiles: StateFlow<List<TileSpec>?> = _tiles.asStateFlow()

    var isFirstRun = false
        private set

    init {
        scope.launch {
            columnCount = columns.first()
            val stored = context.metroDataStore.data.first()[key]
            isFirstRun = stored == null
            _tiles.value = stored?.let(::decode)?.let(::migrate).orEmpty()
            launch { columns.collect { columnCount = it } }
            persistChanges()
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persistChanges() {
        _tiles.filterNotNull().drop(1).debounce(400).collect { list ->
            context.metroDataStore.edit { it[key] = encode(list) }
        }
    }

    /** Replaces the layout; tiles without a position are packed like a fresh start screen. */
    fun replaceAll(tiles: List<TileSpec>) {
        isFirstRun = false
        _tiles.value = migrate(tiles)
    }

    private fun migrate(tiles: List<TileSpec>): List<TileSpec> =
        TilePlacement.settle(TilePlacement.migrate(tiles, columnCount), columnCount)

    /** Applies [transform] and settles the result; [first] is the tile that wins overlaps. */
    private fun change(first: Long? = null, transform: (List<TileSpec>) -> List<TileSpec>) = _tiles.update { list ->
        list?.let { TilePlacement.settle(transform(it), columnCount, first) }
    }

    fun pin(app: AppKey, size: TileSize = TileSize.MEDIUM) = add(TileContent.App(app), size)

    /** Pins a tile at the bottom of the start screen unless an identical one (same app or shortcut) is already pinned. */
    fun add(content: TileContent, size: TileSize): Long? {
        var newId: Long? = null
        _tiles.update { current ->
            val list = current.orEmpty()
            if (content !is TileContent.Widget && list.flatten().any { it.content == content }) return@update list
            val id = nextId(list)
            newId = id
            TilePlacement.settle(list + TileSpec(id, size, content), columnCount)
        }
        return newId
    }

    fun isPinned(content: TileContent): Boolean = _tiles.value.orEmpty().flatten().any { it.content == content }

    /** Top-level tiles plus the children of folders. */
    fun List<TileSpec>.flatten(): List<TileSpec> =
        flatMap { spec -> listOf(spec) + ((spec.content as? TileContent.Folder)?.children.orEmpty()) }

    fun nextId(list: List<TileSpec>): Long = (list.flatten().maxOfOrNull { it.id } ?: 0) + 1

    /** Replaces the whole list through [transform] and settles it; for features that need multi-step edits (folders). */
    fun edit(transform: (List<TileSpec>) -> List<TileSpec>) = change(transform = transform)

    /** Removes a tile, also from inside a folder, and returns it so callers can release resources (e.g. widget ids). */
    fun unpin(id: Long): TileSpec? {
        var removed: TileSpec? = null
        _tiles.update { list ->
            removed = list?.flatten()?.firstOrNull { it.id == id }
            list?.let { TilePlacement.settle(TileFolders.remove(it, id), columnCount) }
        }
        return removed
    }

    /** Steps tile [id] to its next size, keeping its top-left corner; see [TilePlacement.resize]. */
    fun resize(id: Long) = _tiles.update { list -> list?.let { TilePlacement.resize(it, id, columnCount) } }

    /** Gives tile [id] a custom footprint of [width] x [height] cells; see [TilePlacement.setSpan]. */
    fun setSpan(id: Long, width: Int, height: Int) =
        _tiles.update { list -> list?.let { TilePlacement.setSpan(it, id, width, height, columnCount) } }

    /** Drops tile [id] with its top-left corner on cell [x], [y]; see [TilePlacement.moveTo]. */
    fun moveTo(id: Long, x: Int, y: Int) =
        _tiles.update { list -> list?.let { TilePlacement.moveTo(it, id, GridPos(x, y), columnCount) } }

    /**
     * Drops tile [id] onto [targetId]: both become a new folder, or [id] joins the target folder.
     * Returns the folder's id, or null when the two can't merge.
     */
    fun mergeIntoFolder(id: Long, targetId: Long): Long? {
        var folderId: Long? = null
        _tiles.update { list ->
            val merged = list?.let { TileFolders.merge(it, id, targetId, nextId(it)) }
            folderId = merged?.folderId
            merged?.let { TilePlacement.settle(it.tiles, columnCount, it.folderId) } ?: list
        }
        return folderId
    }

    /** Puts a folder's child back on the start screen as a new tile; see [TileFolders.removeFromFolder]. */
    fun removeFromFolder(folderId: Long, childId: Long) = change { TileFolders.removeFromFolder(it, folderId, childId) }

    fun renameFolder(id: Long, name: String) = _tiles.update { list -> list?.let { TileFolders.rename(it, id, name) } }

    fun moveInFolder(folderId: Long, id: Long, targetId: Long) = _tiles.update { list ->
        list?.let { TileFolders.moveInFolder(it, folderId, id, targetId) }
    }

    /** Removes the tiles of an uninstalled package, including those in folders, and returns them. */
    fun removePackage(packageName: String, userSerial: Long): List<TileSpec> {
        var removed: List<TileSpec> = emptyList()
        _tiles.update { list ->
            val gone = mutableListOf<TileSpec>()
            fun TileSpec.belongsToPackage(): Boolean = when (val c = content) {
                is TileContent.App -> c.app.packageName == packageName && c.app.userSerial == userSerial
                is TileContent.Shortcut -> c.packageName == packageName && c.userSerial == userSerial
                is TileContent.Widget ->
                    ComponentName.unflattenFromString(c.provider)?.packageName == packageName && c.userSerial == userSerial
                else -> false
            }
            fun List<TileSpec>.withoutPackage() = filterNot { spec -> spec.belongsToPackage().also { if (it) gone += spec } }
            val updated = list?.withoutPackage()?.map { spec ->
                val folder = spec.content as? TileContent.Folder ?: return@map spec
                spec.copy(content = folder.copy(children = folder.children.withoutPackage()))
            }?.let(TileFolders::tidy)?.let { TilePlacement.settle(it, columnCount) }
            // update may run its block again, so only the last run counts.
            removed = gone
            updated
        }
        return removed
    }

    private fun encode(tiles: List<TileSpec>): String = TileJson.encode(tiles).toString()

    private fun decode(json: String): List<TileSpec> = runCatching { TileJson.decode(JSONArray(json)) }
        .getOrDefault(emptyList())
}

/**
 * JSON form of the start screen. Entries without "type" are app tiles from version 0.1; entries
 * without "x" and "y" predate grid positions and get them from [TilePlacement.migrate].
 */
internal object TileJson {
    fun encode(tiles: List<TileSpec>): JSONArray = JSONArray().apply { tiles.forEach { put(encode(it)) } }

    private fun encode(tile: TileSpec): JSONObject {
        val obj = JSONObject().put("id", tile.id).put("size", tile.size.name)
        if (tile.isPlaced) obj.put("x", tile.x).put("y", tile.y)
        tile.span?.let { obj.put("spanWidth", it.width).put("spanHeight", it.height) }
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
        val span = if (obj.has("spanWidth") && obj.has("spanHeight")) {
            TileSpan(obj.getInt("spanWidth"), obj.getInt("spanHeight"))
        } else {
            null
        }
        return TileSpec(
            obj.getLong("id"), size, content,
            x = obj.optInt("x", TileSpec.UNPLACED), y = obj.optInt("y", TileSpec.UNPLACED), span = span,
        )
    }
}
