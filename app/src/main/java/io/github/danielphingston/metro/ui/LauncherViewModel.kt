package io.github.danielphingston.metro.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.LastAsked
import io.github.danielphingston.metro.data.SavedReply
import io.github.danielphingston.metro.data.BackgroundPhoto
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.DefaultLayout
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.PhotoAccess
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.HistoryData
import io.github.danielphingston.metro.data.Purchases
import io.github.danielphingston.metro.data.SmartFeature
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.ActionIcon
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.tiles.acquireTileResources
import io.github.danielphingston.metro.ui.tiles.releaseTileResources
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarSource
import io.github.danielphingston.metro.live.NotificationHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * A tile ready to draw. [app] is the app the tile belongs to (for app, shortcut and widget
 * tiles); built-in tiles such as weather have none. [children] is filled for folders.
 */
data class ResolvedTile(
    val spec: TileSpec,
    val app: AppInfo?,
    val label: String,
    val children: List<ResolvedTile> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val metro = application as MetroApplication
    val icons: IconCache = metro.icons

    /** The Metro Plus pack: whether it is unlocked, its Play price, and how to buy it. */
    val quickTilesUnlocked: StateFlow<Boolean> = metro.purchases.quickTilesUnlocked
    val quickTilesPrice: StateFlow<String?> = metro.purchases.quickTilesPrice

    fun buyQuickTiles(activity: Activity) = metro.purchases.buyQuickTiles(activity)

    val settings: StateFlow<LauncherSettings> =
        metro.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())

    /**
     * The photo behind the start screen: the only bitmap held for it. Null while loading and
     * whenever no photo background is on; a photo that can't be read switches back to SOLID.
     */
    val backdropImage: StateFlow<ImageBitmap?> = settings
        .map { if (it.showsPhoto) it.backgroundPhoto else null }
        .distinctUntilChanged()
        .mapLatest { uri ->
            uri ?: return@mapLatest null
            val metrics = application.resources.displayMetrics
            try {
                withContext(Dispatchers.IO) {
                    BackgroundPhoto.decode(application, Uri.parse(uri), metrics.widthPixels, metrics.heightPixels)
                }.asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                forgetPhotoIfPermissionGone(application, uri)
                null
            } catch (_: FileNotFoundException) {
                forgetPhotoIfPermissionGone(application, uri)
                null
            } catch (_: Exception) {
                null // Corrupt or transiently unreadable: plain background for now.
            } catch (_: OutOfMemoryError) {
                null // A photo the device can't hold even downsampled: plain background.
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * A failed read is only a reason to drop the user's choice when the app has really lost access to
     * [uri]. A provider or SD card that is briefly unavailable throws the same exceptions but keeps the
     * persisted permission, so the background is retried on the next start instead of erased.
     */
    private suspend fun forgetPhotoIfPermissionGone(application: Application, uri: String) {
        val persisted = application.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
        if (!PhotoAccess.hasPersistedAccess(persisted, uri)) metro.settings.clearUnreadablePhoto()
    }

    val apps: StateFlow<List<AppInfo>> =
        metro.apps.apps.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val defaults: StateFlow<DefaultApps> = metro.apps.defaults

    val notifications: StateFlow<Map<String, AppNotifications>> = NotificationHub.byApp

    /** The assistant tile's saved last reply, shown when its notification is gone. */
    val assistantReply: StateFlow<SavedReply?> = metro.assistantReply.reply
    val lastAsked: StateFlow<LastAsked?> = metro.lastAsked.asked

    /** The saved assistant choice (null: never chose); a change re-resolves the assistant tile. */
    private val assistantStored: Flow<String?> = metro.settings.settings.map { it.assistant }.distinctUntilChanged()

    /** Null while loading, so the start screen can tell "empty" from "not ready yet". */
    val tiles: StateFlow<List<ResolvedTile>?> = combine(metro.tiles.tiles, metro.apps.apps, assistantStored) { specs, apps, assistantSetting ->
        if (specs == null || apps == null) return@combine null
        val byKey = apps.associateBy { it.key }
        // An app update can rename its launcher activity; fall back to the package's main entry.
        val byPackage = apps.groupBy { it.key.packageName to it.key.userSerial }
        fun appFor(pkg: String, serial: Long) = byPackage[pkg to serial]?.first()

        fun resolve(spec: TileSpec): ResolvedTile? = when (val c = spec.content) {
            is TileContent.App -> (byKey[c.app] ?: appFor(c.app.packageName, c.app.userSerial))
                ?.let { ResolvedTile(spec, it, it.label) }
            is TileContent.Shortcut -> appFor(c.packageName, c.userSerial)
                ?.let { ResolvedTile(spec, it, c.label.ifBlank { it.label }) }
            is TileContent.Widget -> {
                val app = ComponentName.unflattenFromString(c.provider)?.let { appFor(it.packageName, c.userSerial) }
                ResolvedTile(spec, app, app?.label ?: "widget")
            }
            TileContent.Weather -> ResolvedTile(spec, null, "weather")
            TileContent.Photos -> ResolvedTile(spec, null, "photos")
            TileContent.People -> ResolvedTile(spec, null, "people")
            is TileContent.QuickSetting -> ResolvedTile(spec, null, c.kind.label)
            is TileContent.Action -> {
                val icon = (c.icon as? ActionIcon.App)?.app
                ResolvedTile(spec, icon?.let { byKey[it] ?: appFor(it.packageName, it.userSerial) }, c.label)
            }
            TileContent.Assistant -> {
                val pkg = AssistantChoice.resolve(assistantSetting, byPackage.keys.mapTo(HashSet()) { it.first })
                val app = pkg?.let { appFor(it, 0L) ?: byPackage.entries.firstOrNull { e -> e.key.first == it }?.value?.first() }
                ResolvedTile(spec, app, app?.let { AssistantChoice.displayName(it.key.packageName, it.label) } ?: "assistant")
            }
            is TileContent.Folder -> ResolvedTile(
                spec, null, c.name.ifBlank { "folder" }, c.children.mapNotNull(::resolve),
            )
        }
        specs.mapNotNull(::resolve)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _calendarGranted = MutableStateFlow(CalendarSource.hasPermission(application))
    val calendarGranted: StateFlow<Boolean> = _calendarGranted.asStateFlow()

    private val _homePresses = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** Emits when the home button is pressed; the value tells whether to animate. */
    val homePresses: SharedFlow<Boolean> = _homePresses.asSharedFlow()

    private val _launcherLeft = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the user left the launcher without a home intent reaching it; see [MainActivity]. */
    val launcherLeft: SharedFlow<Unit> = _launcherLeft.asSharedFlow()

    private val _pinned = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pinned: SharedFlow<Unit> = _pinned.asSharedFlow()

    init {
        viewModelScope.launch {
            val specs = metro.tiles.tiles.filterNotNull().first()
            // A load that failed gives an empty list; wait for a real one rather than save an empty first layout.
            val apps = metro.apps.apps.filterNotNull().first { it.isNotEmpty() }
            if (metro.tiles.isFirstRun && specs.isEmpty()) {
                metro.tiles.replaceAll(DefaultLayout.build(apps, metro.apps.defaults.value, application.packageName))
            }
        }
    }

    fun onHomePressed(animate: Boolean) {
        _homePresses.tryEmit(animate)
    }

    fun onLauncherLeft() {
        _launcherLeft.tryEmit(Unit)
    }

    fun refreshPermissions() {
        _calendarGranted.value = CalendarSource.hasPermission(getApplication())
    }

    /** Remembers [question] as the last one asked, for the assistant tile. */
    fun recordAssistantQuestion(question: String) {
        metro.lastAsked.set(question, System.currentTimeMillis())
    }

    /** Asks Play again whether the quick settings tiles are owned; see [Purchases.recheck]. */
    fun recheckPurchases() = metro.purchases.recheck()

    /** Whether the suggestions feature (Metro Plus) is on, i.e. whether history is kept and shown. */
    private val suggestionsOn: Boolean
        get() = quickTilesUnlocked.value && settings.value.smartOn(SmartFeature.SUGGESTIONS)

    /** Recent searches and launch counts for the empty search. */
    val searchHistory: StateFlow<HistoryData> = metro.searchHistory.data

    /** Counts one use of the "search in" target [id] (see SearchInOrdering) to order the results strip. */
    fun recordSearchInUse(id: String) = metro.searchHistory.recordSearchInUse(id)

    fun recordSearch(query: String) {
        if (suggestionsOn) metro.searchHistory.recordSearch(query, settings.value.searchHistorySize)
    }

    fun clearSearchHistory() = metro.searchHistory.clear()

    fun launch(app: AppInfo, bounds: Rect?, options: Bundle?): Boolean {
        val started = metro.apps.launch(app, bounds, options)
        if (started && suggestionsOn) metro.searchHistory.recordLaunch(app)
        return started
    }
    fun openAppInfo(app: AppInfo) = metro.apps.openAppInfo(app)
    fun uninstall(app: AppInfo) = metro.apps.uninstall(app)

    val tileRepository: TileRepository get() = metro.tiles

    fun isPinned(app: AppInfo): Boolean = metro.tiles.isPinned(TileContent.App(app.key))

    fun pin(app: AppInfo) = pin(TileContent.App(app.key))

    fun isPinned(content: TileContent): Boolean = metro.tiles.isPinned(content)

    /** Pins [content] at medium size once the system holds what it needs (a shortcut must be pinned with it first). */
    fun pin(content: TileContent) {
        viewModelScope.launch {
            acquireTileResources(getApplication(), content)
            addTile(content, TileSize.MEDIUM)
        }
    }

    /** Pins any kind of tile and scrolls the start screen to it. */
    fun addTile(content: TileContent, size: TileSize, span: TileSpan? = null) {
        metro.tiles.add(content, size, span)
        _pinned.tryEmit(Unit)
    }

    fun unpin(id: Long) {
        val removed = metro.tiles.unpin(id) ?: return
        // Also frees the children of a removed folder; shortcut binder calls run off the main thread.
        metro.appScope.launch { releaseTileResources(getApplication(), listOf(removed)) }
    }

    fun resize(id: Long) = metro.tiles.resize(id)
    fun setSpan(id: Long, width: Int, height: Int) = metro.tiles.setSpan(id, width, height)

    /** Sets how tile [id] draws itself; see [io.github.danielphingston.metro.data.TileRepository.setStyle]. */
    fun setStyle(id: Long, style: io.github.danielphingston.metro.data.TileStyle, image: String?) = metro.tiles.setStyle(id, style, image)

    /** Drops tile [id] with its top-left corner on the grid cell [x], [y]. */
    fun moveTo(id: Long, x: Int, y: Int) = metro.tiles.moveTo(id, x, y)

    /** The start screen now shows [count] columns; see [io.github.danielphingston.metro.data.TileRepository.setColumns]. */
    fun setColumns(count: Int) = metro.tiles.setColumns(count)

    /** Groups tiles [ids] with [anchorId] into a new folder or the anchor folder; returns the folder's id, or null if nothing was grouped. */
    fun groupIntoFolder(anchorId: Long, ids: List<Long>): Long? = metro.tiles.groupIntoFolder(anchorId, ids)
    fun removeFromFolder(folderId: Long, childId: Long) = metro.tiles.removeFromFolder(folderId, childId)
    fun renameFolder(id: Long, name: String) = metro.tiles.renameFolder(id, name)
    fun moveInFolder(folderId: Long, id: Long, targetId: Long) = metro.tiles.moveInFolder(folderId, id, targetId)
}
