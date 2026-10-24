package io.github.danielphingston.metro.live

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// TODO(music): implement. Owned by the now-playing feature.

data class NowPlaying(
    val title: String,
    val artist: String,
    val art: ImageBitmap?,
    val isPlaying: Boolean,
)

object MediaSource {
    /**
     * Active media sessions keyed by [io.github.danielphingston.metro.data.AppKey.notificationKey].
     * Collected only while the start screen is visible.
     */
    fun nowPlaying(context: Context): Flow<Map<String, NowPlaying>> = flowOf(emptyMap())
}
