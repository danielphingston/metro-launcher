package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(shortcuts): implement. Owned by the shortcuts feature.

object ShortcutTiles {
    fun launch(context: Context, shortcut: TileContent.Shortcut, bounds: Rect?, options: Bundle?) = Unit
}

@Composable
fun ShortcutTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean) =
    PlaceholderTile(tile, cellSize)

/** Extra context-menu entries for [app] in the app list, e.g. "pin: New message". */
@Composable
fun rememberAppShortcutItems(app: AppInfo, onPin: (TileContent.Shortcut) -> Unit): List<Pair<String, () -> Unit>> =
    emptyList()
