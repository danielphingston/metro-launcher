package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

internal val GroupingBarHeight = 56.dp

/**
 * The bar along the bottom while grouping (see [StartState.groupingAnchorId]): "create folder (n)",
 * or "add to folder (n)" when [anchorIsFolder], and "cancel". The first stays disabled until
 * something is picked. It is chrome like the menus but leaves the page undimmed, since the tiles
 * above it are still being tapped. Entry is animated, exit is immediate.
 */
@Composable
internal fun GroupingBar(
    anchorIsFolder: Boolean,
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        entered,
        modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 },
        exit = ExitTransition.None,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            BarAction("${if (anchorIsFolder) "add to folder" else "create folder"} ($count)", count > 0, onConfirm)
            BarAction("cancel", true, onCancel)
        }
    }
}

@Composable
private fun RowScope.BarAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .weight(1f)
            .height(GroupingBarHeight)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(text, MetroType.button, color = if (enabled) colors.foreground else colors.subtle.copy(alpha = 0.6f), maxLines = 1)
    }
}

/**
 * The round checkbox in a tile's corner while grouping: a ring over a dim backing, and once
 * [picked] filled with the accent and ticked. Drawn in white like the rest of a tile's contents.
 */
@Composable
internal fun GroupCheckbox(picked: Boolean, modifier: Modifier = Modifier) {
    val colors = Metro.colors
    Box(
        modifier
            .size(24.dp)
            .background(if (picked) colors.accent else Color.Black.copy(alpha = 0.35f), CircleShape)
            .border(2.dp, colors.onAccent, CircleShape)
            .drawBehind { if (picked) drawCheck(colors.onSolidAccent, 2.dp.toPx()) },
    )
}

private fun DrawScope.drawCheck(color: Color, stroke: Float) {
    val tick = Path().apply {
        moveTo(size.width * 0.27f, size.height * 0.52f)
        lineTo(size.width * 0.43f, size.height * 0.68f)
        lineTo(size.width * 0.74f, size.height * 0.34f)
    }
    drawPath(tick, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A folder outline with its tab at the top left: the badge that starts grouping. */
internal fun DrawScope.drawFolderGlyph(color: Color, stroke: Float) {
    val left = size.width * 0.26f
    val right = size.width * 0.74f
    val bottom = size.height * 0.68f
    val tabTop = size.height * 0.31f
    val bodyTop = size.height * 0.42f
    val tab = size.width * 0.18f
    val outline = Path().apply {
        moveTo(left, bottom)
        lineTo(left, tabTop)
        lineTo(left + tab, tabTop)
        lineTo(left + tab + (bodyTop - tabTop), bodyTop)
        lineTo(right, bodyTop)
        lineTo(right, bottom)
        close()
    }
    drawPath(outline, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
