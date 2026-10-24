package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.DefaultApps
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(photos): implement. Owned by the photos feature.

object PhotosTiles {
    fun intent(context: Context, defaults: DefaultApps): Intent? = null
}

@Composable
fun PhotosTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) = PlaceholderTile(tile, cellSize)

@Composable
fun PhotosSettingsSection() = Unit
