package io.github.danielphingston.metro.ui.tiles

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.ui.ResolvedTile

// TODO(folders): implement. Owned by the folders feature.

@Composable
fun FolderTile(tile: ResolvedTile, cellSize: Dp, icons: IconCache, monochrome: Boolean, open: Boolean) =
    PlaceholderTile(tile, cellSize)
