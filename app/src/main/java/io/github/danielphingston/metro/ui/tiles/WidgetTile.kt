package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(widgets): implement. Owned by the widgets feature.

object WidgetTiles {
    /** Frees the app widget id when its tile is unpinned. */
    fun release(context: Context, appWidgetId: Int) = Unit
}

@Composable
fun WidgetTile(tile: ResolvedTile, cellSize: Dp) = PlaceholderTile(tile, cellSize)

/** Full-screen overlay to choose a widget and pin it as a tile. */
@Composable
fun WidgetPicker(viewModel: LauncherViewModel, onDismiss: () -> Unit) = Unit
