package io.github.danielphingston.metro.ui.start

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import io.github.danielphingston.metro.ui.tiles.CoinTile
import io.github.danielphingston.metro.ui.tiles.CountdownTile
import io.github.danielphingston.metro.ui.tiles.DiceTile
import io.github.danielphingston.metro.ui.tiles.NotesTile
import io.github.danielphingston.metro.ui.tiles.RoutineTile
import io.github.danielphingston.metro.ui.tiles.rememberTileMetrics
import java.time.LocalDate

private val PreviewSize = 72.dp

/** A tool tile the "metro tiles" panel offers, and what a new one starts as. */
internal data class ToolEntry(val title: String, val subtitle: String, val size: TileSize, val make: (now: Long) -> TileContent.Tool)

internal val ToolEntries = listOf(
    ToolEntry("routine", "tap when it's done: doses at set times each day, or something every few days", TileSize.MEDIUM) { Routines.new(it) },
    ToolEntry("checklist", "a list you tick off; opens right on start", TileSize.WIDE) { TileContent.Notes("", NoteStyle.LIST, created = it) },
    ToolEntry("note", "a few lines to keep in sight; opens right on start", TileSize.MEDIUM) { TileContent.Notes("", NoteStyle.TEXT, created = it) },
    ToolEntry("handwriting", "write or draw with a pen or a finger", TileSize.MEDIUM) { TileContent.Notes("", NoteStyle.INK, created = it) },
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
            item { Section("live") }
            items(live, key = { "live:" + it.label }) { entry ->
                Row(entry.label, LiveSubtitles[entry.label].orEmpty(), { onAddLive(entry) }) {
                    Box(Modifier.size(PreviewSize).tileSurface(), contentAlignment = Alignment.Center) {
                        SearchGlyphs.byId(LiveGlyphs[entry.label] ?: "tiles")?.let { GlyphPicture(it, 40.dp, Metro.colors.onAccent) }
                    }
                }
            }
            item { Section("tools") }
            items(ToolEntries, key = { "tool:" + it.title }) { entry ->
                Row(entry.title, entry.subtitle, { onAddTool(entry) }) { ToolPreview(entry, icons) }
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
    Box(Modifier.size(PreviewSize)) {
        when (content) {
            is TileContent.Routine -> RoutineTile(tile, m, icons)
            is TileContent.Countdown -> CountdownTile(tile, m)
            is TileContent.Coin -> CoinTile(tile, m)
            is TileContent.Dice -> DiceTile(tile, m)
            is TileContent.Notes -> NotesTile(tile, m, open = false)
        }
    }
}

@Composable
private fun Section(text: String) {
    MetroText(
        text,
        MetroType.caption,
        color = Metro.colors.subtle,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Row(title: String, subtitle: String, onClick: () -> Unit, preview: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(onClickLabel = "add $title tile", onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview()
        Column(Modifier.weight(1f)) {
            MetroText(title, MetroType.listItem, maxLines = 1)
            if (subtitle.isNotEmpty()) MetroText(subtitle, MetroType.caption, color = Metro.colors.subtle, maxLines = 3)
        }
    }
}
