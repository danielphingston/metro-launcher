package io.github.danielphingston.metro

import android.app.Notification
import io.github.danielphingston.metro.data.PhotoAccess
import io.github.danielphingston.metro.live.MediaSessionFilter
import io.github.danielphingston.metro.live.NotificationPreviewPolicy
import io.github.danielphingston.metro.live.PreviewSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSourceFixesTest {
    @Test
    fun secretNotificationsShowNoText() {
        assertEquals(PreviewSource.NONE, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_SECRET, true))
        assertEquals(PreviewSource.NONE, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_SECRET, false))
    }

    @Test
    fun privateNotificationsUseOnlyTheirPublicVersion() {
        assertEquals(PreviewSource.PUBLIC, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_PRIVATE, true))
        assertEquals(PreviewSource.NONE, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_PRIVATE, false))
    }

    @Test
    fun publicNotificationsShowTheirContent() {
        assertEquals(PreviewSource.FULL, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_PUBLIC, false))
        assertEquals(PreviewSource.FULL, NotificationPreviewPolicy.sourceFor(Notification.VISIBILITY_PUBLIC, true))
    }

    @Test
    fun artKeyChangesWithAlbumArtistAndDuration() {
        val base = MediaSessionFilter.artKey("pkg", "Intro", "Album A", "Artist", 1000)
        assertEquals(base, MediaSessionFilter.artKey("pkg", "Intro", "Album A", "Artist", 1000))
        assertNotEquals(base, MediaSessionFilter.artKey("pkg", "Intro", "Album B", "Artist", 1000))
        assertNotEquals(base, MediaSessionFilter.artKey("pkg", "Intro", "Album A", "Other", 1000))
        assertNotEquals(base, MediaSessionFilter.artKey("pkg", "Intro", "Album A", "Artist", 2000))
        assertNotEquals(base, MediaSessionFilter.artKey("other", "Intro", "Album A", "Artist", 1000))
    }

    @Test
    fun photoIsOnlyForgottenWhenPermissionIsGone() {
        assertTrue(PhotoAccess.hasPersistedAccess(listOf("content://a/1", "content://a/2"), "content://a/2"))
        assertFalse(PhotoAccess.hasPersistedAccess(listOf("content://a/1"), "content://a/2"))
        assertFalse(PhotoAccess.hasPersistedAccess(emptyList(), "content://a/2"))
    }
}
