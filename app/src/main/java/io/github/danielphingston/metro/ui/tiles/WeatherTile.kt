package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(weather): implement. Owned by the weather feature.

object WeatherTiles {
    /** What tapping the weather tile opens, or null to do nothing. */
    fun intent(context: Context): Intent? = null
}

/** [flipped] toggles every few seconds while the tile is on screen. */
@Composable
fun WeatherTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) = PlaceholderTile(tile, cellSize)

/** Section in Metro settings (location, units). */
@Composable
fun WeatherSettingsSection() = Unit
