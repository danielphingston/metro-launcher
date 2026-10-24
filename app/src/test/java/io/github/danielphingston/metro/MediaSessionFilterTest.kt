package io.github.danielphingston.metro

import android.media.session.PlaybackState
import io.github.danielphingston.metro.live.MediaSessionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionFilterTest {
    @Test
    fun playingPausedBufferingAndConnectingAreShown() {
        listOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_PAUSED,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
        ).forEach { assertTrue("state $it", MediaSessionFilter.isShown(it)) }
    }

    @Test
    fun stoppedErroredAndMissingStatesAreHidden() {
        listOf(
            PlaybackState.STATE_NONE,
            PlaybackState.STATE_STOPPED,
            PlaybackState.STATE_ERROR,
            PlaybackState.STATE_FAST_FORWARDING,
            null,
        ).forEach { assertFalse("state $it", MediaSessionFilter.isShown(it)) }
    }

    @Test
    fun firstNotBlankSkipsNullAndBlank() {
        assertEquals("b", MediaSessionFilter.firstNotBlank(null, "  ", "b", "c"))
        assertEquals("", MediaSessionFilter.firstNotBlank(null, ""))
    }

    @Test
    fun firstNotBlankKeepsTextAsIs() {
        assertEquals(" Daft Punk ", MediaSessionFilter.firstNotBlank(" Daft Punk "))
    }

    @Test
    fun fitLeavesSmallArtAlone() {
        assertEquals(300 to 200, MediaSessionFilter.fit(300, 200, 512))
        assertEquals(512 to 512, MediaSessionFilter.fit(512, 512, 512))
    }

    @Test
    fun fitScalesLongestSideKeepingAspectRatio() {
        assertEquals(512 to 512, MediaSessionFilter.fit(1500, 1500, 512))
        assertEquals(512 to 256, MediaSessionFilter.fit(1024, 512, 512))
        assertEquals(256 to 512, MediaSessionFilter.fit(600, 1200, 512))
    }

    @Test
    fun fitNeverCollapsesASideToZero() {
        assertEquals(512 to 1, MediaSessionFilter.fit(10_000, 3, 512))
    }
}
