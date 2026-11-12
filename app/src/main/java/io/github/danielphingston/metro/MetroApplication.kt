package io.github.danielphingston.metro

import android.app.Application
import androidx.work.Configuration
import io.github.danielphingston.metro.data.AppRepository
import io.github.danielphingston.metro.data.AssistantReplyStore
import io.github.danielphingston.metro.data.LastAskedStore
import io.github.danielphingston.metro.data.CacheTrim
import io.github.danielphingston.metro.data.CrashLog
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.Purchases
import io.github.danielphingston.metro.data.SearchHistory
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileRepository
import io.github.danielphingston.metro.data.TileUndo
import io.github.danielphingston.metro.data.WidgetReclaim
import io.github.danielphingston.metro.live.NotificationAccess
import io.github.danielphingston.metro.live.PeopleSource
import io.github.danielphingston.metro.ui.search.ShortcutIndex
import io.github.danielphingston.metro.ui.search.WhatsAppIndex
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import io.github.danielphingston.metro.ui.tiles.releaseTileResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** The last question asked from search, kept beside the reply; see [LastAskedStore]. */
    lateinit var lastAsked: LastAskedStore
        private set

    /** Undo for start screen edits; widget ids and shortcuts it may bring back stay held. See [TileUndo]. */
    val undo: TileUndo by lazy {
        TileUndo(
            current = { tiles.tiles.value },
            restore = tiles::restore,
            release = { removed -> appScope.launch { releaseTileResources(this@MetroApplication, removed) } },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere below is recorded too.
        CrashLog.install(this)
        settings = SettingsRepository(this)
        searchHistory = SearchHistory(this)
        purchases = Purchases(this, appScope)
        tiles = TileRepository(
            this, appScope, settings.settings.map { it.columns }.distinctUntilChanged(),
            onLoaded = { referenced, undecoded ->
                // A restored backup replaced the layout the undo steps were taken from.
                undo.clear()
                appScope.launch { reclaimWidgetIds(referenced, undecoded) }
            },
        )
        icons = IconCache(this)
        apps = AppRepository(this, appScope, onPackageRemoved = { pkg, serial ->
            val removed = tiles.removePackage(pkg, serial)
            appScope.launch { releaseTileResources(this@MetroApplication, removed) }
            icons.evict(pkg)
        }, onPackageChanged = { pkg ->
            icons.evict(pkg)
            icons.invalidatePack(pkg)
            ShortcutIndex.shared(this).invalidate()
            WhatsAppIndex.shared(this).invalidate()
            WidgetTiles.providerUpdated(this, pkg)
        })
        assistantReply = AssistantReplyStore(this, appScope)
        lastAsked = LastAskedStore(this, appScope, legacy = settings::takeLegacyLastAsked)
        NotificationAccess.rebindIfNeeded(this)
        // The saved reply goes when its reasons go: another assistant chosen, previews off, or no notification access.
        if (!NotificationAccess.isGranted(this)) assistantReply.clear()
        appScope.launch {
            // drop(1): the first value is the stored choice, not a change; a reply of another package is ignored anyway.
            settings.settings.map { it.assistant }.distinctUntilChanged().drop(1).collect {
                assistantReply.clear()
                lastAsked.clear()
            }
        }
        appScope.launch {
            settings.settings.map { it.showNotificationContent }.distinctUntilChanged().collect { if (!it) assistantReply.clear() }
        }
        appScope.launch {
            // Lowering the number of remembered searches trims what is stored at once.
            settings.settings.map { it.searchHistorySize }.distinctUntilChanged().collect { searchHistory.trimSearches(it) }
        }
        appScope.launch {
            // Once per process, when the app list is first known: launch counts of uninstalled apps are dropped.
            val loaded = apps.apps.filterNotNull().first { it.isNotEmpty() }
            searchHistory.pruneLaunches(loaded.mapTo(HashSet()) { it.key.serialize() })
        }
        appScope.launch {
            settings.settings.map { it.iconPack }.distinctUntilChanged().collect { icons.iconPack = it }
        }
    }

    /**
     * Frees app widget ids the host holds that no tile uses: left behind by a backup import or by an
     * activity recreated while a widget was being bound. Any failure of the widget service frees nothing.
     */
    private suspend fun reclaimWidgetIds(referenced: Set<Int>, undecoded: Boolean) {
        if (undecoded) return
        val held = withContext(Dispatchers.Default) {
            try {
                WidgetTiles.host(this@MetroApplication).appWidgetIds.toSet()
            } catch (_: RuntimeException) {
                null
            }
        } ?: return
        // Read again now: a tile may have been added while the ids were fetched.
        val used = referenced + WidgetReclaim.referencedIds(tiles.tiles.value.orEmpty()) +
            undo.snapshots().flatMap(WidgetReclaim::referencedIds)
        val stale = WidgetReclaim.toDelete(held, used, undecoded)
        if (stale.isEmpty()) return
        withContext(Dispatchers.Default) { stale.forEach { WidgetTiles.release(this@MetroApplication, it) } }
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
