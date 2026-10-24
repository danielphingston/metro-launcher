package io.github.danielphingston.metro

import android.app.Application
import io.github.danielphingston.metro.data.AppRepository
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.live.NotificationAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
        settings = SettingsRepository(this)
        tiles = TileRepository(this, appScope)
        icons = IconCache(this)
        apps = AppRepository(this, appScope, onPackageRemoved = { pkg, serial ->
            tiles.removePackage(pkg, serial)
            icons.evict(pkg)
        }, onPackageChanged = { pkg -> icons.evict(pkg) })
        NotificationAccess.rebindIfNeeded(this)
    }
}
