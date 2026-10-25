package io.github.danielphingston.metro.live

import android.app.Notification
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.github.danielphingston.metro.data.AppKey

/**
 * Feeds live tiles from the notification shade. The system binds and calls this only when
 * notifications change, so it costs nothing while the phone is idle.
 */
class MetroNotificationListener : NotificationListenerService() {
    private val userManager by lazy { getSystemService(UserManager::class.java) }

    override fun onListenerConnected() {
        NotificationHub.setConnected(true)
        rebuild()
    }

    override fun onListenerDisconnected() {
        NotificationHub.setConnected(false)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = rebuild()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = rebuild()

    private fun rebuild() {
        val active = try {
            activeNotifications ?: return
        } catch (_: SecurityException) {
            return
        } catch (_: RuntimeException) {
            return // Service not yet fully bound.
        }
        val grouped = active
            .filter(::countsTowardTile)
            .groupBy { AppKey.notificationKey(it.packageName, userManager.getSerialNumberForUser(it.user)) }
        NotificationHub.publish(
            grouped.mapValues { (_, list) ->
                val newestFirst = list.sortedByDescending { it.postTime }
                AppNotifications(count = list.size, previews = newestFirst.mapNotNull(::preview).take(3))
            },
        )
    }

    private fun countsTowardTile(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing || !sbn.isClearable) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return n.category != Notification.CATEGORY_PROGRESS &&
            n.category != Notification.CATEGORY_TRANSPORT &&
            n.category != Notification.CATEGORY_SERVICE
    }

    /**
     * The newest text of [sbn], or null if there is none or it can't be read. Another app's extras
     * are parcelled with its own classes, so reading them can throw (BadParcelableException,
     * ClassCastException); that skips the preview but must not crash a system callback.
     */
    private fun preview(sbn: StatusBarNotification): NotificationPreview? = try {
        previewOf(sbn)
    } catch (_: RuntimeException) {
        null
    }

    private fun previewOf(sbn: StatusBarNotification): NotificationPreview? {
        val n = sbn.notification
        // Respect apps that mark content as secret on the lock screen.
        if (n.visibility == Notification.VISIBILITY_SECRET) return null
        val extras = n.extras
        val lastMessage = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            ?.messages?.lastOrNull()?.text
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = lastMessage
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        return NotificationPreview(title?.toString().orEmpty(), text?.toString().orEmpty(), sbn.postTime)
    }
}
