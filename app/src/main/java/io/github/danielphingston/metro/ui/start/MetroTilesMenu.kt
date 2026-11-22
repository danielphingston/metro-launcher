package io.github.danielphingston.metro.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.Routines
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.ToolLabels
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import io.github.danielphingston.metro.ui.tiles.PickerRow
import io.github.danielphingston.metro.ui.tiles.SectionTitle
import io.github.danielphingston.metro.ui.tiles.ToolFace
import io.github.danielphingston.metro.ui.tiles.rememberTileMetrics
import java.time.LocalDate

private val PreviewSize = 72.dp

/** A tool tile the "metro tiles" panel offers, and what a new one starts as. */
internal data class ToolEntry(val title: String, val subtitle: String, val size: TileSize, val make: (now: Long) -> TileContent.Tool)

internal val ToolEntries = listOf(
    ToolEntry("routine", "tap when it's done, double-tap to undo: doses at set times each day, or something every few days", TileSize.MEDIUM) { Routines.new(it) },
    ToolEntry("notes", "a checklist and a few lines of text; opens right on start", TileSize.WIDE) { TileContent.Notes("", NoteStyle.NOTE, created = it) },
    ToolEntry("handwriting", "write or draw with a pen or a finger; a pen can write right on a wide or large tile", TileSize.MEDIUM) { TileContent.Notes("", NoteStyle.INK, inkId = io.github.danielphingston.metro.data.InkStore.newId(it), created = it) },
    ToolEntry("countdown", "days to a trip, a birthday or a deadline", TileSize.WIDE) {
        TileContent.Countdown("countdown", LocalDate.now().plusDays(7).toEpochDay(), created = it)
    },
    ToolEntry("coin", "heads or tails, with a flip", TileSize.SMALL) { TileContent.Coin(it) },
    ToolEntry("dice", "one to three dice that tumble when you tap", TileSize.MEDIUM) { TileContent.Dice(1, 6, it) },
)

/** The glyph each live tile shows in the panel, by its menu label. */
private val LiveGlyphs = mapOf(
    "clock" to "alarm", "calendar" to "calendar", "weather" to "globe", "photos" to "wallpaper", "people" to "person", "me" to "chat",
)

private val LiveSubtitles = mapOf(
    "clock" to "the time, date and next alarm",
    "calendar" to "today's date and your next appointment",
    "weather" to "conditions now, high and low, and the next days",
    "photos" to "a slideshow of photos you pick",
    "people" to "a mosaic of your contacts' photos",
    "me" to "what's new across your apps",
)

/**
 * "metro tiles" in the add menu: the live tiles that keep themselves up to date, then the tools
 * that are used right on start. Free, unlike the Metro Plus panel it is laid out like.
 */
@Composable
internal fun MetroTilesPanel(
    live: List<LiveTileEntry>,
    icons: IconCache,
    onAddLive: (LiveTileEntry) -> Unit,
    onAddTool: (ToolEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    MetroFlyout(onDismiss) {
        LazyColumn(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                // Keeps taps on the panel from dismissing it.
                .clickable(enabled = false, onClick = {}),
            contentPadding = PaddingValues(top = 20.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
        ) {
            item { MetroText("metro tiles", MetroType.pageTitle, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) }
            item { SectionTitle("live") }
            items(live, key = { "live:" + it.label }) { entry ->
                PickerRow(
                    onClickLabel = "add ${entry.label} tile", onClick = { onAddLive(entry) },
                    preview = {
                        Box(Modifier.size(PreviewSize).tileSurface(), contentAlignment = Alignment.Center) {
                            SearchGlyphs.byId(LiveGlyphs[entry.label] ?: "tiles")?.let { GlyphPicture(it, 40.dp, Metro.colors.onAccent) }
                        }
                    },
                    title = entry.label, subtitle = LiveSubtitles[entry.label].orEmpty(),
                )
            }
            item { SectionTitle("tools") }
            items(ToolEntries, key = { "tool:" + it.title }) { entry ->
                PickerRow(
                    onClickLabel = "add ${entry.title} tile", onClick = { onAddTool(entry) },
                    preview = { ToolPreview(entry, icons) },
                    title = entry.title, subtitle = entry.subtitle,
                )
            }
        }
    }
}

/** A tool's own face at small size, as it would look on start. */
@Composable
private fun ToolPreview(entry: ToolEntry, icons: IconCache) {
    val content = remember(entry) { entry.make(0L) }
    val tile = ResolvedTile(TileSpec(-1, TileSize.SMALL, content), null, ToolLabels.label(content))
    val m = rememberTileMetrics(PreviewSize)
    Box(Modifier.size(PreviewSize)) { ToolFace(tile, m, icons, open = false) }
}
