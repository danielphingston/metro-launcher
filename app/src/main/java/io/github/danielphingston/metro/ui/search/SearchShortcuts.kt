package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import io.github.danielphingston.metro.ui.tiles.ShortcutTiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An app shortcut that matched the search; [userSerial] is the profile it belongs to. */
class ShortcutHit(
    val packageName: String,
    val id: String,
    val userSerial: Long,
    val shortLabel: String,
    val longLabel: String?,
)

/**
 * What one read of the system's shortcuts found. [complete] is false when something could not be
 * read (a locked or paused profile, not the default home app), so the result is not worth caching.
 */
internal class ShortcutLoad<T>(val hits: List<T>, val complete: Boolean)

/**
 * Whether [user]'s shortcuts can be read now: a locked or paused (quiet mode) work profile makes
 * the query throw, so it is skipped instead of costing the personal profile's results.
 */
internal fun shortcutsReadable(context: Context, user: UserHandle): Boolean = try {
    val users = context.getSystemService(UserManager::class.java)
    users.isUserUnlocked(user) && !users.isQuietModeEnabled(user)
} catch (_: RuntimeException) {
    false
}

/**
 * The app shortcuts on the phone, read from the system when a search needs them and kept for
 * [ttlMillis] (one process-wide copy, see [shared]), so reopening the panel doesn't reload them.
 * Nothing tells the search about shortcut or package changes, hence the short lifetime. Empty
 * unless Metro is the default home app, which Android requires for reading other apps' shortcuts.
 */
internal class ShortcutIndex(
    context: Context,
    private val ttlMillis: Long = TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val context = context.applicationContext ?: context

    @Volatile
    private var all: List<ShortcutHit>? = null

    @Volatile
    private var loadedAt = 0L

    /** Up to [limit] shortcuts whose short or long label matches [query]. Suspends off the main thread. */
    suspend fun search(query: String, limit: Int = MAX_HITS): List<ShortcutHit> = withContext(Dispatchers.Default) {
        val hits = all?.takeIf { clock() - loadedAt < ttlMillis } ?: load().let { loaded ->
            // A partial read (a profile that was locked) is used once but not kept, so the next search retries.
            if (loaded.complete) {
                all = loaded.hits
                loadedAt = clock()
            }
            loaded.hits
        }
        SearchRanking.top(hits, query, limit) { listOfNotNull(it.shortLabel, it.longLabel) }.map { it.item }
    }

    /** Forgets the loaded shortcuts, e.g. when an app was installed, updated or removed. */
    fun invalidate() {
        all = null
    }

    private fun load(): ShortcutLoad<ShortcutHit> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
        )
        val hits = ArrayList<ShortcutHit>()
        var complete = true
        try {
            if (!launcherApps.hasShortcutHostPermission()) return ShortcutLoad(emptyList(), false) // Not the default home app.
            for (user in launcherApps.profiles) {
                if (!shortcutsReadable(context, user)) {
                    complete = false
                    continue
                }
                val serial = ShortcutTiles.serialOf(context, user)
                try {
                    launcherApps.getShortcuts(query, user).orEmpty().mapNotNullTo(hits) { info ->
                        val short = info.shortLabel?.toString()?.trim().orEmpty()
                        if (!info.isEnabled || short.isEmpty()) return@mapNotNullTo null
                        ShortcutHit(info.`package`, info.id, serial, short, info.longLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                    }
                } catch (_: SecurityException) {
                    complete = false
                } catch (_: IllegalStateException) {
                    complete = false // The user got locked meanwhile.
                }
            }
        } catch (_: SecurityException) {
            return ShortcutLoad(emptyList(), false)
        } catch (_: IllegalStateException) {
            return ShortcutLoad(emptyList(), false)
        }
        return ShortcutLoad(hits.distinctBy { Triple(it.packageName, it.id, it.userSerial) }, complete)
    }

    companion object {
        private const val MAX_HITS = 4
        const val TTL_MILLIS = 30_000L

        @Volatile
        private var instance: ShortcutIndex? = null

        /** The process-wide index. */
        fun shared(context: Context): ShortcutIndex =
            instance ?: synchronized(this) { instance ?: ShortcutIndex(context).also { instance = it } }
    }
}
