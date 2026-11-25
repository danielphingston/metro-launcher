package io.github.danielphingston.metro.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.LARGE_SCREEN_DP
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.start.GridFrame
import io.github.danielphingston.metro.ui.start.StartColumns
import io.github.danielphingston.metro.ui.start.TileGap
import io.github.danielphingston.metro.ui.start.TilePlacement
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** The spacing presets of the setup's grid step: both side paddings of start, in dp. Pure. */
internal enum class StartSpacing(val label: String, val sideDp: Int) {
    TIGHT("tight", 2),
    NORMAL("normal", LauncherSettings.DEFAULT_START_LEFT_PADDING),
    ROOMY("roomy", 20),
    ;

    companion object {
        /** The preset [settings] match, or null for paddings set by hand in settings. */
        fun of(settings: LauncherSettings): StartSpacing? =
            entries.firstOrNull { settings.startLeftPadding == it.sideDp && settings.startRightPadding == it.sideDp }
    }
}

/** The tiles-per-row choices, as (stored column count, label): medium tiles per row on a phone, columns on a tablet as in settings. Pure. */
internal fun tilesPerRowChoices(large: Boolean): List<Pair<Int, String>> =
    if (large) {
        listOf(LauncherSettings.AUTO_COLUMNS to "auto", 4 to "4", 6 to "6", 8 to "8")
    } else {
        listOf(4 to "2", 6 to "3")
    }

/**
 * The setup's "your start screen" step: tiles per row and spacing, with a preview of the user's
 * own tiles laid out as start will lay them out. Every choice is a setting, saved at once.
 */
@Composable
internal fun StartGridStep(settings: LauncherSettings, repository: SettingsRepository, tiles: List<TileSpec>) {
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val large = LocalConfiguration.current.smallestScreenWidthDp >= LARGE_SCREEN_DP
    // On a phone "auto" shows two per row: it reads as that choice.
    val shown = if (!large && settings.columns == LauncherSettings.AUTO_COLUMNS) LauncherSettings.DEFAULT_COLUMNS else settings.columns

    MetroText("tiles per row", MetroType.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tilesPerRowChoices(large).forEach { (columns, label) ->
            MetroChoice(label, shown == columns, { scope.launch { repository.setColumns(columns) } }, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(20.dp))
    MetroText("spacing", MetroType.sectionTitle, modifier = Modifier.padding(bottom = 8.dp))
    val spacing = StartSpacing.of(settings)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StartSpacing.entries.forEach { preset ->
            MetroChoice(preset.label, spacing == preset, {
                scope.launch { repository.setStartSidePaddings(preset.sideDp, preset.sideDp) }
            }, Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(20.dp))
    GridPreview(tiles, settings, large)
    Spacer(Modifier.height(10.dp))
    MetroText("More options in settings, start.", MetroType.caption, color = colors.subtle)
}

/**
 * A small picture of start: [specs] placed by [TilePlacement] for the columns [settings] give, in
 * a frame the shape of the screen. Drawn once per change of tiles or settings; nothing animates.
 */
@Composable
private fun GridPreview(specs: List<TileSpec>, settings: LauncherSettings, large: Boolean) {
    val colors = Metro.colors
    val config = LocalConfiguration.current
    val screenWidth = config.screenWidthDp.toFloat().coerceAtLeast(1f)
    val screenHeight = config.screenHeightDp.toFloat().coerceAtLeast(1f)
    val gap = TileGap.value
    val left = settings.startLeftPadding.toFloat()
    val right = settings.startRightPadding.toFloat()
    val gridSpan = screenWidth - left - right + gap
    val columns = StartColumns.resolve(settings.columns, large, gridSpan)
    val placements = remember(specs, columns) { TilePlacement.layout(specs, columns) }
    val perRow = if (large) "$columns columns" else "${columns / 2} tiles per row"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxWidth(0.55f)
                .aspectRatio((screenWidth / screenHeight).coerceIn(0.4f, 1.5f))
                .border(1.dp, colors.subtle)
                .clipToBounds()
                .semantics { contentDescription = "preview of your start screen, $perRow" },
        ) {
            val scale = size.width / screenWidth
            val cell = StartColumns.cellDp(gridSpan, columns, large)
            val frame = GridFrame.centred(
                contentWidthPx = size.width,
                leftPaddingPx = left * scale,
                gridWidthPx = (cell * columns - gap) * scale,
                rightPaddingPx = right * scale,
                // About a status bar, then the top padding.
                topPx = (STATUS_BAR_DP + settings.startTopPadding).coerceAtLeast(0f) * scale,
                cellPx = cell * scale,
                gapPx = gap * scale,
            )
            placements.forEach { placement ->
                val rect = frame.rectOf(placement)
                if (rect.isUsable && rect.top < size.height) {
                    drawRect(colors.accent, Offset(rect.left, rect.top), Size(rect.width, rect.height))
                }
            }
        }
    }
}

/** Roughly a status bar's height, for the preview only. */
private const val STATUS_BAR_DP = 24f
