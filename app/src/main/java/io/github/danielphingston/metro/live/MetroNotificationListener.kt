package io.github.danielphingston.metro.live

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.AssistantLastReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Feeds live tiles from the notification shade. The system binds and calls this only when
 * notifications change, so it costs nothing while the phone is idle.
 */
class MetroNotificationListener : NotificationListenerService() {
    /**
     * Rebuilds run on one background thread, one at a time, so results are published in order and
     * the binder calls and extras parsing stay off the main thread.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val requests = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (request in requests) {
                // A burst of posts/removals (e.g. clear all) becomes a single rebuild.
                delay(REBUILD_DEBOUNCE_MS)
                rebuild()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onListenerConnected() {
        NotificationHub.setConnected(true)
        requests.trySend(Unit)
    }

    override fun onListenerDisconnected() {
        NotificationHub.setConnected(false)
        // Access taken away (not just a rebind): the saved assistant reply goes with it.
        if (!NotificationAccess.isGranted(this)) (application as? MetroApplication)?.assistantReply?.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        requests.trySend(Unit)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        requests.trySend(Unit)
    }

    private suspend fun rebuild() {
        val active = try {
            activeNotifications ?: return
        } catch (_: SecurityException) {
            return
        } catch (_: RuntimeException) {
            return // Service not yet fully bound.
        }
        // Disconnected while the rebuild was pending: don't resurrect what setConnected(false) cleared.
        if (!NotificationHub.connected.value) return
        val grouped = active
            .filter(::countsTowardTile)
            .groupBy { AppKey.notificationKey(it.packageName, UserSerials.of(this, it.user)) }
        val published = grouped.mapValues { (_, list) ->
                // Lazily, so only as many notifications as the (at most 3) previews need get parsed.
                val previews = list.sortedByDescending { it.postTime }
                    .asSequence()
                    .mapNotNull(::preview)
                    .take(MAX_PREVIEWS)
                    .toList()
                AppNotifications(count = list.size, previews = previews)
            }
        NotificationHub.publish(published)
        saveAssistantReply(published)
    }

    /**
     * Keeps the assistant's newest reply so the tile can still show it after the notification is
     * gone. Previews already skip SECRET notifications, and nothing is kept while the user has
     * turned notification content off, so only what the tile would show is stored.
     */
    private suspend fun saveAssistantReply(published: Map<String, AppNotifications>) {
        val app = application as? MetroApplication ?: return
        val settings = app.settings.settings.first()
        val installed = app.apps.apps.value?.mapTo(HashSet()) { it.key.packageName } ?: return
        val pkg = AssistantChoice.resolve(settings.assistant, installed) ?: return
        val preview = published[AppKey.notificationKey(pkg, 0L)]?.previews?.firstOrNull()
        val label = app.apps.apps.value?.firstOrNull { it.key.packageName == pkg && it.key.userSerial == 0L }
            ?.let { AssistantChoice.displayName(pkg, it.label) } ?: AssistantChoice.displayName(pkg, pkg)
        app.assistantReply.save(AssistantLastReply.toSave(pkg, label, preview, settings.showNotificationContent))
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
        // Respect apps that mark their content as secret.
        if (!NotificationPreviewPolicy.showsContent(n.visibility)) return null
        val source = n
        val extras = source.extras
        val lastMessage = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(source)
            ?.messages?.lastOrNull()?.text
        val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = lastMessage
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return null
        return NotificationPreview(title?.toString().orEmpty(), text?.toString().orEmpty(), sbn.postTime)
    }

    private companion object {
        const val REBUILD_DEBOUNCE_MS = 150L
        const val MAX_PREVIEWS = 3
    }
}
