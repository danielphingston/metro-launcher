package io.github.danielphingston.metro.ui.tiles

import androidx.compose.runtime.Composable
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.NowPlaying
import io.github.danielphingston.metro.ui.start.TileMetrics

// TODO(music): implement. Owned by the now-playing feature.

@Composable
internal fun NowPlayingFront(app: AppInfo, size: TileSize, info: NowPlaying, m: TileMetrics) = Unit

@Composable
internal fun NowPlayingBack(app: AppInfo, size: TileSize, info: NowPlaying, m: TileMetrics) = Unit
