package io.github.danielphingston.metro.ui

import android.app.Application
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
import io.github.danielphingston.metro.data.TileSpec
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

data class ResolvedTile(val spec: TileSpec, val app: AppInfo)

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
        specs.mapNotNull { spec ->
            val app = byKey[spec.app] ?: byPackage[spec.app.packageName to spec.app.userSerial]?.first()
            app?.let { ResolvedTile(spec, it) }
        }
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

    fun isPinned(app: AppInfo): Boolean = metro.tiles.tiles.value.orEmpty().any { it.app == app.key }

    fun pin(app: AppInfo) {
        metro.tiles.pin(app.key)
        _pinned.tryEmit(Unit)
    }

    fun unpin(id: Long) = metro.tiles.unpin(id)
    fun resize(id: Long) = metro.tiles.resize(id)
    fun move(id: Long, targetId: Long) = metro.tiles.move(id, targetId)
}
