package io.github.danielphingston.metro.live

import android.app.PendingIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One counted notification as the me tile sees it. [preview] is null when its text must not be shown (SECRET) or can't be read. */
data class MeNotification(
    /** The system's notification key; identifies it for a tap. */
    val key: String,
    /** [io.github.danielphingston.metro.data.AppKey.notificationKey] of the posting app. */
    val appKey: String,
    val packageName: String,
    val postTime: Long,
    val preview: NotificationPreview?,
    val intent: PendingIntent?,
)

/** An app that has counted notifications; [newestKey] and [newestIntent] are those of its newest one. */
data class MeApp(val appKey: String, val packageName: String, val count: Int, val newest: Long, val newestKey: String, val newestIntent: PendingIntent? = null)

/** [apps] and [recent] are newest first; [recent] is capped so only a few notifications get their extras parsed. */
data class MeSnapshot(val total: Int, val apps: List<MeApp>, val recent: List<MeNotification>) {
    companion object {
        val EMPTY = MeSnapshot(0, emptyList(), emptyList())
    }
}

/** What the listener hands over for each counted notification; [preview] is read lazily, only for the newest few. */
class MeInput(
    val key: String,
    val appKey: String,
    val packageName: String,
    val postTime: Long,
    val intent: PendingIntent?,
    val preview: () -> NotificationPreview?,
)

/**
 * In-memory bridge for the me tile, fed by [MetroNotificationListener] from the same filtered list
 * that feeds the unread counts, so both agree. It changes only on listener callbacks.
 */
object MeFeed {
    const val RECENT_LIMIT = 5

    private val _snapshot = MutableStateFlow(MeSnapshot.EMPTY)
    val snapshot: StateFlow<MeSnapshot> = _snapshot.asStateFlow()

    internal fun publish(value: MeSnapshot) {
        _snapshot.value = value
    }

    internal fun clear() {
        _snapshot.value = MeSnapshot.EMPTY
    }

    /** Pure: the snapshot for [inputs] (already filtered), newest first. */
    internal fun build(inputs: List<MeInput>, recentLimit: Int = RECENT_LIMIT): MeSnapshot {
        val sorted = inputs.sortedByDescending { it.postTime }
        val apps = sorted.groupBy { it.appKey }.map { (appKey, list) ->
            MeApp(appKey, list.first().packageName, list.size, list.first().postTime, list.first().key, list.first().intent)
        }.sortedByDescending { it.newest }
        val recent = sorted.take(recentLimit).map {
            MeNotification(it.key, it.appKey, it.packageName, it.postTime, it.preview(), it.intent)
        }
        return MeSnapshot(inputs.size, apps, recent)
    }
}

/**
 * Which notifications count toward an app tile's unread count ([countsForAppTile]) and the me tile
 * ([counts], which also leaves out the system's own); pure so it can be tested.
 */
internal object NotificationFilter {
    /** The system's own packages: their entries ("N more notifications", USB, charging...) summarise or announce, never message. */
    val SYSTEM_PACKAGES = setOf("com.android.systemui", "android")

    /** True for a package that must never count: the system UI, or a system app with no launcher entry. */
    fun isSystemNoise(packageName: String, isSystemApp: Boolean, launchable: Boolean): Boolean =
        packageName in SYSTEM_PACKAGES || (isSystemApp && !launchable)

    /** For the me tile: what counts on an app tile, without the system's own noise. */
    fun counts(
        packageName: String,
        ownPackage: String,
        ongoing: Boolean,
        clearable: Boolean,
        flags: Int,
        category: String?,
        isSystemApp: Boolean,
        launchable: Boolean,
    ): Boolean = !isSystemNoise(packageName, isSystemApp, launchable) &&
        countsForAppTile(packageName, ownPackage, ongoing, clearable, flags, category)

    /** For an app tile's unread count: anything but Metro's own, ongoing, summary and progress-like entries. */
    fun countsForAppTile(
        packageName: String,
        ownPackage: String,
        ongoing: Boolean,
        clearable: Boolean,
        flags: Int,
        category: String?,
    ): Boolean {
        if (packageName == ownPackage) return false
        if (ongoing || !clearable) return false
        if (flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return false
        return category != android.app.Notification.CATEGORY_PROGRESS &&
            category != android.app.Notification.CATEGORY_TRANSPORT &&
            category != android.app.Notification.CATEGORY_SERVICE
    }
}
