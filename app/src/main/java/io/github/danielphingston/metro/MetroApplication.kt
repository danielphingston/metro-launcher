package io.github.danielphingston.metro

import android.app.Application
import androidx.work.Configuration
import io.github.danielphingston.metro.data.AppRepository
import io.github.danielphingston.metro.data.AssistantReplyStore
import io.github.danielphingston.metro.data.CacheTrim
import io.github.danielphingston.metro.data.CrashLog
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.Purchases
import io.github.danielphingston.metro.data.SearchHistory
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.live.PeopleSource
import io.github.danielphingston.metro.ui.search.ShortcutIndex
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import io.github.danielphingston.metro.ui.tiles.releaseTileResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. The launcher process is long-lived, so these are created once. */
class MetroApplication : Application(), Configuration.Provider {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var apps: AppRepository
        private set
    lateinit var icons: IconCache
        private set
    lateinit var settings: SettingsRepository
        private set
    lateinit var tiles: TileRepository
        private set
    lateinit var purchases: Purchases
        private set
    /** Recent searches and launch counts for the empty search; local only, see [SearchHistory]. */
    lateinit var searchHistory: SearchHistory
        private set

    /** The assistant tile's saved last reply; see [AssistantReplyStore]. */
    lateinit var assistantReply: AssistantReplyStore
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is recorded too.
        CrashLog.install(this)
        settings = SettingsRepository(this)
        searchHistory = SearchHistory(this)
        purchases = Purchases(this, appScope)
        tiles = TileRepository(this, appScope, settings.settings.map { it.columns }.distinctUntilChanged())
        icons = IconCache(this)
        apps = AppRepository(this, appScope, onPackageRemoved = { pkg, serial ->
            val removed = tiles.removePackage(pkg, serial)
            appScope.launch { releaseTileResources(this@MetroApplication, removed) }
            icons.evict(pkg)
        }, onPackageChanged = { pkg ->
            icons.evict(pkg)
            icons.invalidatePack(pkg)
            ShortcutIndex.shared(this).invalidate()
        })
        assistantReply = AssistantReplyStore(this, appScope)
        NotificationAccess.rebindIfNeeded(this)
        // The saved reply goes when its reasons go: another assistant chosen, previews off, or no notification access.
        if (!NotificationAccess.isGranted(this)) assistantReply.clear()
        appScope.launch {
            // drop(1): the first value is the stored choice, not a change; a reply of another package is ignored anyway.
            settings.settings.map { it.assistant }.distinctUntilChanged().drop(1).collect { assistantReply.clear() }
        }
        appScope.launch {
            settings.settings.map { it.showNotificationContent }.distinctUntilChanged().collect { if (!it) assistantReply.clear() }
        }
        appScope.launch {
            settings.settings.map { it.iconPack }.distinctUntilChanged().collect { icons.iconPack = it }
        }
    }

    /**
     * WorkManager (only the weather refresh uses it) starts on first use, not at every process
     * start. If its database can't be opened it reports that to the handler below, which would
     * otherwise be an uncaught exception on every start; the weather tile then just stops refreshing.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { /* Nothing to do: weather refreshes are dropped. */ }
            .build()

    /**
     * The launcher stays in memory while other apps run, so it gives its bitmap caches back as the
     * system asks (see [CacheTrim]). The start background photo is not a cache and stays.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val keep = CacheTrim.keepFraction(level)
        if (keep >= 1f) return
        icons.trimMemory(keep)
        PeopleSource.trimMemory(keep)
        ShortcutTiles.trimMemory(keep)
    }
}
