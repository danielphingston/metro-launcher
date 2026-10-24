package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(people): implement. Owned by the people feature.

object PeopleTiles {
    fun intent(context: Context): Intent? = null
}

@Composable
fun PeopleTile(tile: ResolvedTile, cellSize: Dp, flipped: Boolean) = PlaceholderTile(tile, cellSize)
