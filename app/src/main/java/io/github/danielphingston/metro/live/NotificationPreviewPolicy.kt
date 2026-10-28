package io.github.danielphingston.metro.live

import android.app.Notification

/** The pure decision behind [MetroNotificationListener]'s previews, free of framework objects for unit tests. */
internal object NotificationPreviewPolicy {
    /**
     * SECRET never shows its content. PRIVATE (the default for most apps) only hides content on a
     * secure lock screen; the launcher is shown unlocked, so like the notification shade it shows it.
     */
    fun showsContent(visibility: Int): Boolean = visibility != Notification.VISIBILITY_SECRET
}
