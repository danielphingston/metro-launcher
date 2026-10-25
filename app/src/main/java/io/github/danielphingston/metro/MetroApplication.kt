package io.github.danielphingston.metro

import android.app.Application
import io.github.danielphingston.metro.data.AppRepository
import io.github.danielphingston.metro.data.CrashLog
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.ui.tiles.releaseTileResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. The launcher process is long-lived, so these are created once. */
class MetroApplication : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var apps: AppRepository
        private set
    lateinit var icons: IconCache
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var tiles: TileRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is recorded too.
        CrashLog.install(this)
        settings = SettingsRepository(this)
        tiles = TileRepository(this, appScope, settings.settings.map { it.columns }.distinctUntilChanged())
        icons = IconCache(this)
        apps = AppRepository(this, appScope, onPackageRemoved = { pkg, serial ->
            val removed = tiles.removePackage(pkg, serial)
            appScope.launch { releaseTileResources(this@MetroApplication, removed) }
            icons.evict(pkg)
        }, onPackageChanged = { pkg ->
            icons.evict(pkg)
            icons.invalidatePack(pkg)
        })
        NotificationAccess.rebindIfNeeded(this)
        appScope.launch {
            settings.settings.map { it.iconPack }.distinctUntilChanged().collect { icons.iconPack = it }
        }
    }
}
