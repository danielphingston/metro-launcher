package io.github.danielphingston.metro.live

import android.app.Notification

/** Where a notification's preview text may come from. */
internal enum class PreviewSource {
    /** The notification's own content. */
    FULL,

    /** Only its `publicVersion`, the redacted content the app made for a lock screen. */
    PUBLIC,

    /** No text at all; the notification still counts toward the badge. */
    NONE,
}

/** The pure decision behind [MetroNotificationListener]'s previews, free of framework objects for unit tests. */
internal object NotificationPreviewPolicy {
    /**
     * SECRET never shows anything. PRIVATE (also the default) asks to hide its content on a secure lock
     * screen, so only the app's redacted public version may be used, and with none there is no text.
     * PUBLIC (and anything unknown) shows the content.
     */
    fun sourceFor(visibility: Int, hasPublicVersion: Boolean): PreviewSource = when (visibility) {
        Notification.VISIBILITY_SECRET -> PreviewSource.NONE
        Notification.VISIBILITY_PRIVATE -> if (hasPublicVersion) PreviewSource.PUBLIC else PreviewSource.NONE
        else -> PreviewSource.FULL
    }
}
