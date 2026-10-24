package io.github.danielphingston.metro.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Rect
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.data.DefaultLayout
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.CalendarSource
import io.github.danielphingston.metro.live.NotificationHub
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val metro = application as MetroApplication
    val icons: IconCache = metro.icons

    val settings: StateFlow<LauncherSettings> =
        metro.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, LauncherSettings())

    val apps: StateFlow<List<AppInfo>> =
        metro.apps.apps.map { it.orEmpty() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val defaults: StateFlow<DefaultApps> = metro.apps.defaults

    val notifications: StateFlow<Map<String, AppNotifications>> = NotificationHub.byApp

    /** Null while loading, so the start screen can tell "empty" from "not ready yet". */
    val tiles: StateFlow<List<ResolvedTile>?> = combine(metro.tiles.tiles, metro.apps.apps) { specs, apps ->
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

    private val _pinned = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pinned: SharedFlow<Unit> = _pinned.asSharedFlow()

    init {
        viewModelScope.launch {
            val specs = metro.tiles.tiles.filterNotNull().first()
            val apps = metro.apps.apps.filterNotNull().first()
            if (metro.tiles.isFirstRun && specs.isEmpty()) {
                metro.tiles.replaceAll(DefaultLayout.build(apps, metro.apps.defaults.value, application.packageName))
            }
        }
    }

    fun onHomePressed(animate: Boolean) {
        _homePresses.tryEmit(animate)
    }

    fun refreshPermissions() {
        _calendarGranted.value = CalendarSource.hasPermission(getApplication())
    }

    fun launch(app: AppInfo, bounds: Rect?, options: Bundle?) = metro.apps.launch(app, bounds, options)
    fun openAppInfo(app: AppInfo) = metro.apps.openAppInfo(app)
    fun uninstall(app: AppInfo) = metro.apps.uninstall(app)

    val tileRepository: TileRepository get() = metro.tiles

    fun isPinned(app: AppInfo): Boolean = metro.tiles.isPinned(TileContent.App(app.key))

    fun pin(app: AppInfo) = addTile(TileContent.App(app.key), TileSize.MEDIUM)

    /** Pins any kind of tile and scrolls the start screen to it. */
    fun addTile(content: TileContent, size: TileSize) {
        metro.tiles.add(content, size)
        _pinned.tryEmit(Unit)
    }

    fun unpin(id: Long) {
        val removed = metro.tiles.unpin(id) ?: return
        (removed.content as? TileContent.Widget)?.let { WidgetTiles.release(getApplication(), it.appWidgetId) }
        (removed.content as? TileContent.Shortcut)?.let { ShortcutTiles.release(getApplication(), it) }
    }
    fun resize(id: Long) = metro.tiles.resize(id)
    fun move(id: Long, targetId: Long) = metro.tiles.move(id, targetId)
}
