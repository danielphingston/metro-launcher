package io.github.danielphingston.metro.ui.search

import android.content.Context
import android.content.pm.LauncherApps
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
 * The app shortcuts on the phone, read once from the system when the first search needs them and
 * dropped with the overlay. Empty unless Metro is the default home app, which Android requires
 * for reading other apps' shortcuts.
 */
internal class ShortcutIndex(private val context: Context) {
    private var all: List<ShortcutHit>? = null

    /** Up to [limit] shortcuts whose short or long label matches [query]. Suspends off the main thread. */
    suspend fun search(query: String, limit: Int = MAX_HITS): List<ShortcutHit> = withContext(Dispatchers.Default) {
        val hits = all ?: load().also { all = it }
        SearchRanking.top(hits, query, limit) { listOfNotNull(it.shortLabel, it.longLabel) }.map { it.item }
    }

    private fun load(): List<ShortcutHit> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        return try {
            if (!launcherApps.hasShortcutHostPermission()) return emptyList()
            val query = LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
            )
            launcherApps.profiles.flatMap { user ->
                val serial = ShortcutTiles.serialOf(context, user)
                launcherApps.getShortcuts(query, user).orEmpty().mapNotNull { info ->
                    val short = info.shortLabel?.toString()?.trim().orEmpty()
                    if (!info.isEnabled || short.isEmpty()) return@mapNotNull null
                    ShortcutHit(info.`package`, info.id, serial, short, info.longLabel?.toString()?.trim()?.takeIf { it.isNotEmpty() })
                }
            }.distinctBy { Triple(it.packageName, it.id, it.userSerial) }
        } catch (_: SecurityException) {
            emptyList() // Not the default home app.
        } catch (_: IllegalStateException) {
            emptyList() // The user is locked.
        }
    }

    private companion object {
        const val MAX_HITS = 4
    }
}
