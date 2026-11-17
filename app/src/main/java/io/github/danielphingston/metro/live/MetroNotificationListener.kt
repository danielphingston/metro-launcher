package io.github.danielphingston.metro.live

import android.app.Notification
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.AssistantLastReply
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.TrackImages
import io.github.danielphingston.metro.live.tracking.TrackItem
import io.github.danielphingston.metro.live.tracking.TrackKind
import io.github.danielphingston.metro.live.tracking.TrackingFeed
import io.github.danielphingston.metro.live.tracking.TrackingParser
import io.github.danielphingston.metro.live.tracking.TrackingReader
import io.github.danielphingston.metro.live.tracking.TrackingSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val trackingReader by lazy { TrackingReader(this) }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (request in requests) {
                // A burst of posts/removals (e.g. clear all) becomes a single rebuild.
                delay(REBUILD_DEBOUNCE_MS)
                try {
                    rebuild()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed rebuild (e.g. an unreadable settings file) must not take the process down
                    // or end this loop: the next notification change simply tries again.
                }
            }
        }
        watchTrackingTile()
    }

    /** A tracking tile just pinned (or unpinned) gets its content from one rebuild, not from the next notification. */
    private fun watchTrackingTile() {
        val app = application as? MetroApplication ?: return
        scope.launch {
            app.tiles.tiles
                .map { list -> list.orEmpty().flatMap { listOf(it) + ((it.content as? TileContent.Folder)?.children.orEmpty()) }.any { it.content == TileContent.Tracking } }
                .distinctUntilChanged()
                .drop(1)
                .collect { requests.trySend(Unit) }
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
        MeFeed.clear()
        TrackingFeed.clear()
        // Access taken away (not just a rebind): the saved assistant reply goes with it.
        if (!NotificationAccess.isGranted(this)) {
            (application as? MetroApplication)?.assistantReply?.clear()
            (application as? MetroApplication)?.parcels?.let { store -> scope.launch { store.clear() } }
        }
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
        val counted = active.filter(::countsTowardTile)
        // The me tile also leaves out the system's own entries; app tiles count them as they always have.
        val forMe = counted.filter { !isSystemNoise(it.packageName) }
        val grouped = counted
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
        MeFeed.publish(
            MeFeed.build(
                forMe.map {
                    MeInput(
                        it.key, AppKey.notificationKey(it.packageName, UserSerials.of(this, it.user)), it.packageName,
                        it.postTime, it.notification.contentIntent, { preview(it) },
                    )
                },
            ),
        )
        saveAssistantReply(published)
        // Its own failure must not cost the other tiles their update, nor theirs this one.
        try {
            updateTracking(active)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TrackingFeed.clear()
        }
    }

    /**
     * Feeds the tracking tile from the notifications already in hand: no extra work is scheduled,
     * and nothing is read at all unless a tracking tile is on the start screen. Only apps known to
     * post trips, orders or parcels (and Android 16's promoted Live Updates) get their extras read.
     */
    private suspend fun updateTracking(active: Array<StatusBarNotification>) {
        val app = application as? MetroApplication ?: return
        if (!app.tiles.isPinned(TileContent.Tracking)) {
            TrackingFeed.clear()
            trackingReader.retain(emptySet())
            return
        }
        val showContent = app.settings.settings.first().showNotificationContent
        val now = System.currentTimeMillis()
        val items = ArrayList<TrackItem>()
        val intents = HashMap<String, android.app.PendingIntent>()
        val images = HashMap<String, TrackImages>()
        for (sbn in active) {
            if (items.size >= MAX_TRACKED) break
            if (!trackingReader.isCandidate(sbn, packageName)) continue
            val appKey = AppKey.notificationKey(sbn.packageName, UserSerials.of(this, sbn.user))
            val input = trackingReader.input(sbn, appKey) ?: continue
            val item = try {
                TrackingParser.parse(input, now)
            } catch (_: RuntimeException) {
                null
            } ?: continue
            items += item
            trackingReader.intent(sbn)?.let { intents[item.key] = it }
            // Pictures are content too: none for SECRET notifications or with content turned off.
            if (showContent && !item.secret && item.stage != ParcelStage.DELIVERED) {
                trackingReader.images(sbn, item.kind, TRACK_IMAGE_PX)?.let { images[item.key] = it }
            }
        }
        trackingReader.retain(images.keys)
        app.parcels.update(items.filter { it.kind == TrackKind.PARCEL }, now, showContent)
        TrackingFeed.publish(TrackingSnapshot(items.filter { it.stage != ParcelStage.DELIVERED }, intents, images))
    }

    /**
     * Keeps the assistant's newest reply so the tile can still show it after the notification is
     * gone. Previews already skip SECRET notifications, and nothing is kept while the user has
     * turned notification content off, so only what the tile would show is stored.
     */
    private suspend fun saveAssistantReply(published: Map<String, AppNotifications>) {
        val app = application as? MetroApplication ?: return
        // The assistant is a Metro Plus feature: without it nothing is kept (and nothing stays kept after a refund).
        if (!app.purchases.quickTilesUnlocked.value) {
            app.assistantReply.clear()
            return
        }
        val settings = app.settings.settings.first()
        val ownSerial = UserSerials.of(this, Process.myUserHandle())
        val installed = app.apps.apps.value?.mapTo(HashSet()) { it.key.packageName } ?: return
        val pkg = AssistantChoice.resolve(settings.assistant, installed) ?: return
        val preview = published[AppKey.notificationKey(pkg, ownSerial)]?.previews?.firstOrNull()
        val label = app.apps.apps.value?.firstOrNull { it.key.packageName == pkg && it.key.userSerial == ownSerial }
            ?.let { AssistantChoice.displayName(pkg, it.label) } ?: AssistantChoice.displayName(pkg, pkg)
        app.assistantReply.save(AssistantLastReply.toSave(pkg, label, preview, settings.showNotificationContent))
    }

    private fun countsTowardTile(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        return NotificationFilter.countsForAppTile(sbn.packageName, packageName, sbn.isOngoing, sbn.isClearable, n.flags, n.category)
    }

    /** The system UI, or a system app with no launcher entry; see [NotificationFilter.isSystemNoise]. */
    private fun isSystemNoise(pkg: String): Boolean {
        // Only look the package up when it could matter: the cheap check comes first.
        if (pkg in NotificationFilter.SYSTEM_PACKAGES) return true
        val system = isSystemApp(pkg)
        val launchable = !system || packageManager.getLaunchIntentForPackage(pkg) != null
        return NotificationFilter.isSystemNoise(pkg, system, launchable)
    }

    private fun isSystemApp(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
    } catch (_: Exception) {
        false
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
        const val MAX_TRACKED = 12
        /** Tile pictures are at most this many pixels on a side (a large tile's picture on a dense screen). */
        const val TRACK_IMAGE_PX = 240
    }
}
