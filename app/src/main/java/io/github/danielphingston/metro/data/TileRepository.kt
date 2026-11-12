package io.github.danielphingston.metro.data

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.danielphingston.metro.live.UserSerials
import io.github.danielphingston.metro.ui.start.GridPos
import io.github.danielphingston.metro.ui.start.StartColumns
import io.github.danielphingston.metro.ui.start.TilePlacement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

enum class TileSize(val width: Int, val height: Int) {
    SMALL(1, 1), MEDIUM(2, 2), WIDE(4, 2), LARGE(4, 4);

    /** The resize button's order: each press grows the tile one size, and the largest goes back to small. */
    fun next(): TileSize = when (this) {
        SMALL -> MEDIUM
        MEDIUM -> WIDE
        WIDE -> LARGE
        LARGE -> SMALL
    }
}

/** What a tile shows and does. */
sealed interface TileContent {
    /** An app's main activity; may carry live content (notifications, clock, calendar, media). */
    data class App(val app: AppKey) : TileContent

    /** A pinned app shortcut (Android's equivalent of a Windows Phone secondary tile). */
    data class Shortcut(val packageName: String, val shortcutId: String, val userSerial: Long, val label: String) :
        TileContent

    /**
     * A hosted Android app widget. [provider] is a flattened ComponentName. A [transparent] widget
     * has no tile background, so its own (often rounded) design shows; else it sits on the accent tile.
     */
    data class Widget(
        val appWidgetId: Int,
        val provider: String,
        val userSerial: Long,
        val transparent: Boolean = false,
    ) : TileContent

    data object Weather : TileContent
    data object Photos : TileContent
    data object People : TileContent

    /** A system toggle or shortcut (flashlight, ringer, battery...) with its live state; one tile per [kind]. */
    data class QuickSetting(val kind: QuickSettingKind) : TileContent

    /** The assistant tile (Metro Plus): follows the shared assistant setting; shows its latest reply and the last question asked. */
    data object Assistant : TileContent

    /** The "me" tile: what is new across all apps, from the notification listener. Persists nothing; one per layout. */
    data object Me : TileContent

    /**
     * Something pinned from the search results that isn't an app or an app shortcut: a contact, a
     * settings page, a timer, a web search. [target] says what it opens and [icon] what it shows.
     */
    data class Action(val label: String, val icon: ActionIcon?, val target: LaunchTarget) : TileContent

    /** Windows Phone 8.1 tile folder. Children are app or shortcut tiles. */
    data class Folder(val name: String, val children: List<TileSpec>) : TileContent
}

/** The quick-setting tiles. [id] is the name saved in the layout, so it must never change. */
enum class QuickSettingKind(val id: String, val label: String) {
    FLASHLIGHT("flashlight", "flashlight"),
    DO_NOT_DISTURB("dnd", "do not disturb"),
    RINGER("ringer", "ringer"),
    AUTO_ROTATE("rotate", "auto-rotate"),
    BRIGHTNESS("brightness", "brightness"),
    BATTERY("battery", "battery");

    companion object {
        /** Null for a kind this version doesn't know, e.g. one saved by a newer build or since removed. */
        fun fromId(id: String?): QuickSettingKind? = entries.firstOrNull { it.id == id }
    }
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
/** How an app tile draws itself. The [needsImage] styles show [TileSpec.image]. */
enum class TileStyle(val id: String, val label: String, val needsImage: Boolean = false) {
    ICON("icon", "icon and name"),
    ICON_ONLY("iconOnly", "icon only"),
    NAME_ONLY("name", "name only"),
    CUSTOM_ICON("customIcon", "custom icon", needsImage = true),
    FULL_IMAGE("image", "full tile image", needsImage = true),
    IMAGE_ICON("imageIcon", "image behind icon", needsImage = true),
    ;

    companion object {
        fun fromId(id: String?): TileStyle = entries.firstOrNull { it.id == id } ?: ICON
    }
}

data class TileSpec(
    val id: Long,
    val size: TileSize,
    val content: TileContent,
    val x: Int = UNPLACED,
    val y: Int = UNPLACED,
    /** When set, replaces [size] with any footprint (width 1..columns, height 1..[TileSpan.MAX_HEIGHT]). */
    val span: TileSpan? = null,
    /** How an app or shortcut tile draws itself (see [TileStyles.canStyle]); other tiles ignore it. */
    val style: TileStyle = TileStyle.ICON,
    /** The picked photo (a content URI with a persisted read grant) for styles that [TileStyle.needsImage]. */
    val image: String? = null,
    /** A picked icon replacing the tile's own: "pack:<package>:<drawable>" or "glyph:<id>". */
    val icon: String? = null,
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
class TileRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val columns: Flow<Int>,
    /**
     * Called on the main thread each time a stored layout was loaded (at start, after a backup
     * import): the app widget ids that layout references (see [WidgetReclaim.referencedIds]) and
     * whether some entries could not be decoded. Lets the app reclaim ids no tile uses.
     */
    private val onLoaded: (referenced: Set<Int>, undecoded: Boolean) -> Unit = { _, _ -> },
) {
    private val key = stringPreferencesKey("tiles")

    /** The layout as it was before the latest save, kept while it still parses; see [TileStore.load]. */
    private val backupKey = stringPreferencesKey("tiles_backup")

    /**
     * Entries of the stored layout this version can't decode (a tile kind from a newer build, say).
     * They are written back with every save, so downgrading and upgrading again loses nothing.
     */
    @Volatile
    private var carried = TileJson.Unread()

    /** Edits not yet written; the debounce in [persistChanges] waits, [flush] doesn't. */
    @Volatile
    private var dirty = false

    /**
     * The columns the start screen shows now. With the automatic setting the screen reports its
     * width-dependent count through [setColumns]; edits settle the layout for that count.
     */
    @Volatile
    private var columnCount = LauncherSettings.DEFAULT_COLUMNS

    /** Null until loaded. [isFirstRun] tells whether a layout has ever been saved. */
    private val _tiles = MutableStateFlow<List<TileSpec>?>(null)
    val tiles: StateFlow<List<TileSpec>?> = _tiles.asStateFlow()

    var isFirstRun = false
        private set

    /** Guards [pendingAdds] and the switch of [_tiles] from null to loaded. */
    private val loadLock = Any()

    /** Tiles added before the layout loaded; the load puts them after the stored tiles. */
    private val pendingAdds = ArrayList<PendingAdd>()

    private class PendingAdd(val content: TileContent, val size: TileSize, val span: TileSpan?)

    /**
     * Whether the layout in use is the stored one itself. A missing or damaged one (the backup or an
     * empty layout stands in) says nothing about which widgets the user has, so no id is reclaimed for it.
     */
    private fun readAsStored(stored: String?): Boolean = stored != null && TileJson.isReadable(stored)

    private fun undecoded(): Boolean = carried.top.isNotEmpty() || carried.children.values.any { it.isNotEmpty() }

    init {
        scope.launch {
            columns.first().takeIf { it != LauncherSettings.AUTO_COLUMNS }?.let { columnCount = it }
            val prefs = context.metroDataStore.data.first()
            val stored = prefs[key]
            isFirstRun = stored == null
            val loaded = TileStore.load(stored, prefs[backupKey])
            carried = loaded.unread
            val pruned = loaded.tiles.let(::load).let { pruneDeadWidgets(it) }
            val hadPending = publishLoaded(pruned)
            onLoaded(WidgetReclaim.referencedIds(loaded.tiles) + WidgetReclaim.referencedIds(_tiles.value.orEmpty()), undecoded() || !readAsStored(stored))
            launch { columns.collect { if (it != LauncherSettings.AUTO_COLUMNS) columnCount = it } }
            persistChanges(initialIsEdit = hadPending)
        }
    }

    /**
     * Reads the layout from the store again, after a backup was restored into it. The restored
     * layout replaces the one held in memory, so a later save can't write the old one back.
     */
    suspend fun reloadFromStore() {
        val prefs = context.metroDataStore.data.first()
        val stored = prefs[key]
        val loaded = TileStore.load(stored, prefs[backupKey])
        carried = loaded.unread
        isFirstRun = false
        val pruned = loaded.tiles.let(::load).let { pruneDeadWidgets(it) }
        _tiles.value = pruned
        onLoaded(WidgetReclaim.referencedIds(loaded.tiles) + WidgetReclaim.referencedIds(pruned), undecoded() || !readAsStored(stored))
    }

    /**
     * Publishes the first loaded layout with the tiles that were added while it loaded. True when
     * there were any: that value is then an edit that still has to be saved.
     */
    private fun publishLoaded(stored: List<TileSpec>): Boolean = synchronized(loadLock) {
        val queued = pendingAdds.toList()
        pendingAdds.clear()
        _tiles.value = queued.fold(stored) { list, add -> withAdded(list, add.content, add.size, add.span)?.first ?: list }
        queued.isNotEmpty()
    }

    /** [persistChanges] normally skips the first value (the loaded layout); [initialIsEdit] saves it too. */
    @OptIn(FlowPreview::class)
    private suspend fun persistChanges(initialIsEdit: Boolean) {
        _tiles.filterNotNull().let { if (initialIsEdit) it else it.drop(1) }.onEach { dirty = true }.debounce(400).collect { save(it) }
    }

    private suspend fun save(list: List<TileSpec>) {
        dirty = false
        val encoded = TileJson.encode(list, carried).toString()
        try {
            context.metroDataStore.edit { prefs ->
                // Keep the previous layout as a fallback, but never replace a good backup with a bad one.
                prefs[key]?.takeIf { it != encoded && TileJson.isReadable(it) }?.let { prefs[backupKey] = it }
                prefs[key] = encoded
            }
        } catch (_: IOException) {
            dirty = true // Disk full or unwritable: the next change (or flush) saves the whole layout again.
        }
    }

    /**
     * Writes an edit that is still waiting out the debounce right now. Called when the launcher
     * stops: the process may be killed any time after that, and an edit made just before leaving
     * would otherwise be lost.
     */
    fun flush() {
        if (!dirty) return
        val list = _tiles.value ?: return
        scope.launch { save(list) }
    }

    /**
     * Drops widget tiles whose app widget id is unknown to the system, as after restoring a
     * backup onto another device: they could only ever show a placeholder. Conservative: see
     * [WidgetPruning.isDead]. Querying the ids of a provider owned by another app may be refused by
     * the system (a RuntimeException, caught below): such a tile is simply kept, so this can never
     * delete a live widget, at worst it prunes nothing.
     */
    private suspend fun pruneDeadWidgets(tiles: List<TileSpec>): List<TileSpec> {
        if (tiles.none { it.content is TileContent.Widget }) return tiles
        // Binder calls, one of them large (every provider on the device): off the main thread, and the list once.
        return withContext(Dispatchers.Default) { pruneDeadWidgetsNow(tiles) }
    }

    private fun pruneDeadWidgetsNow(tiles: List<TileSpec>): List<TileSpec> {
        val manager = AppWidgetManager.getInstance(context)
        val ownSerial = UserSerials.of(context, Process.myUserHandle())
        val installed = try {
            manager.installedProviders.mapTo(HashSet()) { it.provider }
        } catch (_: RuntimeException) {
            null // No answer: no provider counts as installed, so every tile is kept.
        }
        return tiles.filterNot { spec ->
            val widget = spec.content as? TileContent.Widget ?: return@filterNot false
            try {
                val provider = ComponentName.unflattenFromString(widget.provider)
                WidgetPruning.isDead(
                    ownProfile = widget.userSerial == ownSerial,
                    infoKnown = manager.getAppWidgetInfo(widget.appWidgetId) != null,
                    providerInstalled = provider != null && installed != null && provider in installed,
                    idsOfProvider = provider?.let { manager.getAppWidgetIds(it) },
                    id = widget.appWidgetId,
                )
            } catch (_: RuntimeException) {
                false // The widget service didn't answer: that is no reason to delete anything.
            }
        }
    }

    /** Replaces the layout; tiles without a position are packed like a fresh start screen. */
    fun replaceAll(tiles: List<TileSpec>) {
        isFirstRun = false
        _tiles.value = migrate(tiles)
    }

    /** The start screen now shows [count] columns (the automatic setting changes with the width); the next edit settles for it. */
    fun setColumns(count: Int) {
        columnCount = count
    }

    /**
     * The saved layout, as it is: positions are only settled when the user edits, so a start
     * screen that was wider (a tablet turned, a foldable unfolded) comes back unchanged. Only a
     * layout from before positions existed is packed, into the current columns.
     */
    private fun load(tiles: List<TileSpec>): List<TileSpec> {
        if (tiles.none { it.isPlaced }) return migrate(tiles)
        return TilePlacement.settle(TilePlacement.clampPositions(tiles, StartColumns.MAX_AUTO), StartColumns.MAX_AUTO)
    }

    private fun migrate(tiles: List<TileSpec>): List<TileSpec> =
        TilePlacement.settle(TilePlacement.migrate(tiles, columnCount), columnCount)

    /** Applies [transform] and settles the result; [first] is the tile that wins overlaps. */
    private fun change(first: Long? = null, transform: (List<TileSpec>) -> List<TileSpec>) = _tiles.update { list ->
        list?.let { TilePlacement.settle(transform(it), columnCount, first) }
    }

    fun pin(app: AppKey, size: TileSize = TileSize.MEDIUM) = add(TileContent.App(app), size)

    /** Pins a tile at the bottom of the start screen unless an identical one (same app or shortcut) is already pinned. A [span] replaces the footprint of [size]. */
    fun add(content: TileContent, size: TileSize, span: TileSpan? = null): Long? {
        var newId: Long? = null
        synchronized(loadLock) {
            if (_tiles.value == null) {
                // The layout is still loading: a tile published now would be overwritten by it. The
                // load adds this one after the stored tiles; nobody needs its id (callers ignore the result).
                pendingAdds += PendingAdd(content, size, span)
                return null
            }
            _tiles.update { list ->
                val added = list?.let { withAdded(it, content, size, span) }
                newId = added?.second
                added?.first ?: list
            }
        }
        return newId
    }

    /** [list] with the new tile (and its id) settled in, or null when an identical app or shortcut is already pinned. */
    private fun withAdded(list: List<TileSpec>, content: TileContent, size: TileSize, span: TileSpan?): Pair<List<TileSpec>, Long>? {
        if (content !is TileContent.Widget && list.flatten().any { it.content == content }) return null
        val id = nextId(list)
        return TilePlacement.settle(list + TileSpec(id, size, content, span = span), columnCount) to id
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

    /**
     * Steps tile [id] to its next size: a folder child in place (see [TileFolders.resizeChild]),
     * otherwise keeping its top-left corner; see [TilePlacement.resize].
     */
    fun resize(id: Long) = _tiles.update { list ->
        list?.let { TileFolders.resizeChild(it, id) ?: TilePlacement.resize(it, id, columnCount) }
    }

    /** Gives tile [id] a custom footprint of [width] x [height] cells; see [TilePlacement.setSpan]. */
    fun setSpan(id: Long, width: Int, height: Int) =
        _tiles.update { list -> list?.let { TilePlacement.setSpan(it, id, width, height, columnCount) } }

    /** Sets how tile [id] (top level or in a folder) draws itself; [image] is kept only for styles that need one. */
    fun setStyle(id: Long, style: TileStyle, image: String?) = _tiles.update { list ->
        list?.let { TileStyles.set(it, id, style, image) }
    }

    /** Sets the picked icon of tile [id] (top level or in a folder); null restores its own icon. */
    fun setIcon(id: Long, icon: String?) = _tiles.update { list ->
        list?.let { TileStyles.setIcon(it, id, icon) }
    }

    /** Drops tile [id] with its top-left corner on cell [x], [y]; see [TilePlacement.moveTo]. */
    fun moveTo(id: Long, x: Int, y: Int) =
        _tiles.update { list -> list?.let { TilePlacement.moveTo(it, id, GridPos(x, y), columnCount) } }

    /**
     * Groups tiles [ids] with [anchorId] into a new folder, or into the anchor if it is a folder;
     * see [TileFolders.groupIntoFolder]. Returns the folder's id, or null when nothing was grouped.
     */
    fun groupIntoFolder(anchorId: Long, ids: List<Long>): Long? {
        var folderId: Long? = null
        _tiles.update { list ->
            val grouped = list?.let { TileFolders.groupIntoFolder(it, anchorId, ids, nextId(it)) }
            folderId = grouped?.folderId
            grouped?.let { TilePlacement.settle(it.tiles, columnCount, it.folderId) } ?: list
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

}

/** When a stored widget tile can safely be dropped; see [TileRepository]. */
internal object TileStyles {
    /** App and shortcut tiles: the ones with an icon and a name to arrange. */
    fun canStyle(tile: TileSpec): Boolean = tile.content is TileContent.App || tile.content is TileContent.Shortcut ||
        tile.content is TileContent.Action || tile.content is TileContent.Folder

    /**
     * Styles [tile] offers. A folder draws its grid of app icons as it always does; it can drop its
     * name, add a photo behind the grid, or show only a photo (the apps appear when it opens).
     */
    fun styles(tile: TileSpec): List<TileStyle> =
        if (tile.content is TileContent.Folder) {
            listOf(TileStyle.ICON, TileStyle.ICON_ONLY, TileStyle.IMAGE_ICON, TileStyle.FULL_IMAGE)
        } else {
            TileStyle.entries
        }

    fun set(tiles: List<TileSpec>, id: Long, style: TileStyle, image: String?): List<TileSpec> = tiles.map { tile ->
        when {
            tile.id == id -> tile.copy(style = style, image = image.takeIf { style.needsImage })
            tile.content is TileContent.Folder -> tile.copy(content = tile.content.copy(children = set(tile.content.children, id, style, image)))
            else -> tile
        }
    }

    fun setIcon(tiles: List<TileSpec>, id: Long, icon: String?): List<TileSpec> = tiles.map { tile ->
        when {
            tile.id == id -> tile.copy(icon = icon)
            tile.content is TileContent.Folder -> tile.copy(content = tile.content.copy(children = setIcon(tile.content.children, id, icon)))
            else -> tile
        }
    }
}

/** Which app widget ids our host holds for nothing, so they can be freed. */
internal object WidgetReclaim {
    /** The widget ids of [tiles], including the tiles inside folders. */
    fun referencedIds(tiles: List<TileSpec>): Set<Int> {
        val ids = HashSet<Int>()
        fun visit(list: List<TileSpec>): Unit = list.forEach { spec ->
            when (val c = spec.content) {
                is TileContent.Widget -> ids += c.appWidgetId
                is TileContent.Folder -> visit(c.children)
                else -> Unit
            }
        }
        visit(tiles)
        return ids
    }

    /**
     * The ids to delete: held by the host, used by no tile. Nothing at all when some stored entries
     * couldn't be decoded ([undecoded]): one of them may be a widget tile of a newer build.
     */
    fun toDelete(hostIds: Collection<Int>, referenced: Set<Int>, undecoded: Boolean): List<Int> =
        if (undecoded) emptyList() else hostIds.filter { it !in referenced }
}

internal object WidgetPruning {
    /**
     * Dead means the system gave no info for the id although the provider is installed and the
     * system doesn't list the id among the provider's widgets. Anything unsure (a provider that is
     * missing or updating, another profile, no answer) keeps the tile.
     */
    fun isDead(ownProfile: Boolean, infoKnown: Boolean, providerInstalled: Boolean, idsOfProvider: IntArray?, id: Int) =
        ownProfile && !infoKnown && providerInstalled && idsOfProvider != null && id !in idsOfProvider
}

/** Loads the stored layout, falling back to the backup when the current one is unreadable. */
internal object TileStore {
    class Loaded(val tiles: List<TileSpec>, val unread: TileJson.Unread)

    /**
     * The tiles of [stored], or of [backup] when [stored] isn't a valid layout (a half-written or
     * damaged value must not read as "no tiles", which the next edit would then save over the
     * good backup). Both unusable: an empty layout.
     */
    fun load(stored: String?, backup: String?): Loaded {
        for (raw in listOfNotNull(stored, backup)) {
            val read = TileJson.read(raw) ?: continue
            return Loaded(read.tiles, read.unread)
        }
        return Loaded(emptyList(), TileJson.Unread())
    }
}

/**
 * JSON form of the start screen. Entries without "type" are app tiles from version 0.1; entries
 * without "x" and "y" predate grid positions and get them from [TilePlacement.migrate].
 */
internal object TileJson {
    /**
     * Saved entries this version can't decode: [top] at the top level and [children] by folder id.
     * They are written back unchanged so that a layout from a newer build survives a round trip.
     */
    class Unread(val top: List<JSONObject> = emptyList(), val children: Map<Long, List<JSONObject>> = emptyMap())

    class Read(val tiles: List<TileSpec>, val unread: Unread)

    /** Positions beyond these are corrupt; they keep arithmetic and the placement loops bounded. */
    private const val MAX_X = 64
    private const val MAX_Y = 4096

    fun encode(tiles: List<TileSpec>): JSONArray = encode(tiles, Unread())

    fun encode(tiles: List<TileSpec>, unread: Unread): JSONArray = JSONArray().apply {
        tiles.forEach { put(encode(it, unread)) }
        unread.top.forEach { put(it) }
    }

    /** Whether [json] is a JSON array, i.e. a layout rather than a damaged value. */
    fun isReadable(json: String): Boolean = runCatching { JSONArray(json) }.isSuccess

    /** The tiles of a stored layout plus what couldn't be decoded; null if [json] isn't a JSON array. */
    fun read(json: String): Read? {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val top = ArrayList<JSONObject>()
        val children = HashMap<Long, List<JSONObject>>()
        val tiles = decodeAll(array, top, children)
        return Read(tiles, Unread(top, children))
    }

    private fun decodeAll(array: JSONArray, unread: MutableList<JSONObject>, children: MutableMap<Long, List<JSONObject>>) =
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val tile = runCatching { decode(obj, children) }.getOrNull()
            if (tile == null) unread += obj
            tile
        }

    private fun encode(tile: TileSpec, unread: Unread = Unread()): JSONObject {
        val obj = JSONObject().put("id", tile.id).put("size", tile.size.name)
        if (tile.isPlaced) obj.put("x", tile.x).put("y", tile.y)
        tile.span?.let { obj.put("spanWidth", it.width).put("spanHeight", it.height) }
        if (tile.style != TileStyle.ICON) obj.put("style", tile.style.id)
        tile.image?.let { obj.put("image", it) }
        tile.icon?.let { obj.put("icon", it) }
        when (val c = tile.content) {
            is TileContent.App -> obj.put("type", "app").put("app", c.app.serialize())
            is TileContent.Shortcut -> obj.put("type", "shortcut").put("package", c.packageName)
                .put("shortcut", c.shortcutId).put("serial", c.userSerial).put("label", c.label)
            is TileContent.Widget -> obj.put("type", "widget").put("widgetId", c.appWidgetId)
                .put("provider", c.provider).put("serial", c.userSerial).apply { if (c.transparent) put("transparent", true) }
            TileContent.Weather -> obj.put("type", "weather")
            TileContent.Photos -> obj.put("type", "photos")
            TileContent.People -> obj.put("type", "people")
            is TileContent.QuickSetting -> obj.put("type", "quick").put("kind", c.kind.id)
            TileContent.Assistant -> obj.put("type", "assistant")
            TileContent.Me -> obj.put("type", "me")
            is TileContent.Action -> obj.put("type", "action").put("label", c.label).put("target", c.target.toJson()).apply {
                when (val icon = c.icon) {
                    is ActionIcon.Glyph -> put("glyph", icon.id)
                    is ActionIcon.App -> put("iconApp", icon.app.serialize())
                    is ActionIcon.Contact -> put("contact", icon.uri)
                    null -> Unit
                }
            }
            is TileContent.Folder -> obj.put("type", "folder").put("name", c.name).put("children", encode(c.children).apply { unread.children[tile.id]?.forEach { put(it) } })
        }
        return obj
    }

    fun decode(array: JSONArray): List<TileSpec> = decodeAll(array, ArrayList(), HashMap())

    private fun decode(obj: JSONObject, unreadChildren: MutableMap<Long, List<JSONObject>>): TileSpec? {
        val size = runCatching { TileSize.valueOf(obj.getString("size")) }.getOrDefault(TileSize.MEDIUM)
        val content: TileContent = when (obj.optString("type", "app")) {
            "app" -> TileContent.App(AppKey.parse(obj.getString("app")) ?: return null)
            "shortcut" -> TileContent.Shortcut(
                obj.getString("package"), obj.getString("shortcut"), obj.getLong("serial"), obj.optString("label"),
            )
            "widget" -> TileContent.Widget(
                obj.getInt("widgetId"), obj.getString("provider"), obj.getLong("serial"), obj.optBoolean("transparent"),
            )
            "weather" -> TileContent.Weather
            "photos" -> TileContent.Photos
            "people" -> TileContent.People
            "quick" -> TileContent.QuickSetting(QuickSettingKind.fromId(obj.optString("kind")) ?: return null)
            "assistant" -> TileContent.Assistant
            "me" -> TileContent.Me
            "action" -> TileContent.Action(
                obj.optString("label"),
                when {
                    obj.has("glyph") -> ActionIcon.Glyph(obj.getString("glyph"))
                    obj.has("iconApp") -> AppKey.parse(obj.getString("iconApp"))?.let { ActionIcon.App(it) }
                    obj.has("contact") -> ActionIcon.Contact(obj.getString("contact"))
                    else -> null
                },
                LaunchTarget.fromJson(obj.getJSONObject("target")) ?: return null,
            )
            "folder" -> {
                val unread = ArrayList<JSONObject>()
                val kids = decodeAll(obj.getJSONArray("children"), unread, HashMap())
                if (unread.isNotEmpty()) unreadChildren[obj.getLong("id")] = unread
                TileContent.Folder(obj.optString("name"), kids)
            }
            else -> return null
        }
        val span = if (obj.has("spanWidth") && obj.has("spanHeight")) {
            TileSpan(obj.getInt("spanWidth").coerceIn(1, MAX_X), obj.getInt("spanHeight").coerceIn(1, TileSpan.MAX_HEIGHT))
        } else {
            null
        }
        // A position outside any plausible grid is corrupt: clamp it rather than let the layout chase it.
        val x = obj.optInt("x", TileSpec.UNPLACED)
        val y = obj.optInt("y", TileSpec.UNPLACED)
        val placed = x >= 0 && y >= 0
        return TileSpec(
            obj.getLong("id"), size, content,
            x = if (placed) x.coerceAtMost(MAX_X) else TileSpec.UNPLACED,
            y = if (placed) y.coerceAtMost(MAX_Y) else TileSpec.UNPLACED,
            span = span,
            style = TileStyle.fromId(obj.optString("style").ifEmpty { null }),
            image = obj.optString("image").ifEmpty { null },
            icon = obj.optString("icon").ifEmpty { null },
        )
    }
}
