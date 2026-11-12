package io.github.danielphingston.metro.ui.tiles

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** The layout switch tile: what it says and what a tap does. */
object LayoutSwitchTiles {
    /** What a switch tile shows: its name line and, for "next layout", where it goes. */
    data class Face(val title: String, val target: String?)

    /** [content]'s face among [set]: the layout's name, "next layout" and the next name, or "layout removed". */
    fun face(content: TileContent.LayoutSwitch, set: LayoutSet?): Face {
        val id = content.layoutId ?: return Face("next layout", set?.next()?.name)
        return Face(set?.byId(id)?.name ?: "layout removed", null)
    }

    /** How the tile reads to TalkBack and in the start screen's tile list. */
    fun label(content: TileContent.LayoutSwitch, set: LayoutSet?): String =
        face(content, set).let { face -> face.target?.let { "${face.title}, $it" } ?: face.title }

    /**
     * Switches to the tile's layout, or the next one. A layout that is gone, or no other layout
     * to go to, opens the layouts settings page instead.
     */
    fun activate(context: Context, content: TileContent.LayoutSwitch) {
        val app = context.applicationContext as MetroApplication
        val set = app.layouts.layouts.value
        val target = content.layoutId?.let { set?.byId(it) } ?: if (content.layoutId == null) set?.next() else null
        if (target == null) {
            context.startActivity(SettingsActivity.intent(context, SettingsPage.LAYOUTS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        app.appScope.launch { app.layouts.switchTo(target.id) }
    }
}

@Composable
fun LayoutSwitchTile(tile: ResolvedTile, cellSize: Dp) {
    val content = tile.spec.content as? TileContent.LayoutSwitch ?: return
    val app = LocalContext.current.applicationContext as? MetroApplication
    val set by (app?.layouts?.layouts ?: MutableStateFlow(null)).collectAsStateWithLifecycle()
    val face = LayoutSwitchTiles.face(content, set)
    val m = rememberTileMetrics(cellSize)
    val size = tile.spec.size
    Box(
        Modifier.fillMaxSize().tileSurface().semantics(mergeDescendants = true) {
            contentDescription = LayoutSwitchTiles.label(content, set)
            role = Role.Button
        },
    ) {
        if (size == TileSize.SMALL) {
            LayoutGlyph(m.dp(38f), Modifier.align(Alignment.Center))
            return@Box
        }
        val glyph = when (size) {
            TileSize.MEDIUM -> 52f
            TileSize.WIDE -> 60f
            else -> 96f
        }
        LayoutGlyph(m.dp(glyph), Modifier.align(Alignment.Center))
        face.target?.let {
            MetroText(
                it,
                MetroType.style(m.text(if (size == TileSize.LARGE) 28f else 20f), FontWeight.Light),
                color = Metro.colors.onAccent,
                maxLines = 1,
                modifier = Modifier.padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(6f)),
            )
        }
        TileLabel(face.title, m, Modifier.align(Alignment.BottomStart))
    }
}

/** Two stacked tile outlines with an arrow round them: one start layout giving way to another. */
@Composable
internal fun LayoutGlyph(size: Dp, modifier: Modifier = Modifier, color: Color = Metro.colors.onAccent) {
    Canvas(modifier.size(size)) { drawLayoutGlyph(this.size.minDimension, color) }
}

internal fun DrawScope.drawLayoutGlyph(s: Float, color: Color) {
    val line = Stroke(s * 0.07f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val corner = CornerRadius(s * 0.04f)
    // The front layout, and the one behind it showing at its top and right edges.
    drawRoundRect(color, Offset(s * 0.14f, s * 0.34f), Size(s * 0.46f, s * 0.46f), corner, style = line)
    val back = Path().apply {
        moveTo(s * 0.3f, s * 0.34f)
        lineTo(s * 0.3f, s * 0.18f)
        lineTo(s * 0.76f, s * 0.18f)
        lineTo(s * 0.76f, s * 0.64f)
        lineTo(s * 0.6f, s * 0.64f)
    }
    drawPath(back, color, style = line)
    // The arrow from the back layout's corner round to the front.
    val arrow = Path().apply {
        moveTo(s * 0.88f, s * 0.5f)
        quadraticTo(s * 0.9f, s * 0.9f, s * 0.6f, s * 0.9f)
    }
    drawPath(arrow, color, style = line)
    drawLine(color, Offset(s * 0.6f, s * 0.9f), Offset(s * 0.68f, s * 0.83f), line.width, StrokeCap.Round)
    drawLine(color, Offset(s * 0.6f, s * 0.9f), Offset(s * 0.68f, s * 0.96f), line.width, StrokeCap.Round)
}
