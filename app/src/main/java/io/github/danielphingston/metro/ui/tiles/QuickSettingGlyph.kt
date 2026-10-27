package io.github.danielphingston.metro.ui.tiles

import android.media.AudioManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.live.QuickState
import io.github.danielphingston.metro.ui.theme.Metro
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A white line glyph for a quick-setting tile, dimmed along with the tile while the setting is off. */
@Composable
internal fun QuickSettingGlyph(kind: QuickSettingKind, state: QuickState, size: Dp, modifier: Modifier = Modifier) {
    val color = Metro.colors.onAccent.copy(alpha = if (state.on) 1f else 0.7f)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val line = Stroke(s * 0.075f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            QuickSettingKind.FLASHLIGHT -> flashlight(s, color, line, state.on)
            QuickSettingKind.DO_NOT_DISTURB -> {
                drawCircle(color, s * 0.4f, Offset(s * 0.5f, s * 0.5f), style = line)
                drawLine(color, Offset(s * 0.28f, s * 0.5f), Offset(s * 0.72f, s * 0.5f), s * 0.1f, StrokeCap.Round)
            }
            QuickSettingKind.RINGER ->
                if (state.level == AudioManager.RINGER_MODE_VIBRATE) vibrate(s, color, line) else bell(s, color, line, silent = state.level == AudioManager.RINGER_MODE_SILENT)
            QuickSettingKind.AUTO_ROTATE -> rotate(s, color, line)
            QuickSettingKind.BRIGHTNESS -> sun(s, color, line)
            QuickSettingKind.WIFI -> wifi(s, color, line)
            QuickSettingKind.MOBILE_DATA -> signalBars(s, color)
            QuickSettingKind.BLUETOOTH -> bluetooth(s, color, line)
            QuickSettingKind.AIRPLANE -> airplane(s, color)
            QuickSettingKind.LOCATION -> pin(s, color, line)
            QuickSettingKind.HOTSPOT -> hotspot(s, color, line)
            QuickSettingKind.BATTERY -> battery(s, color, line, state.level)
        }
    }
}

private fun DrawScope.flashlight(s: Float, color: Color, line: Stroke, on: Boolean) {
    val head = Path().apply {
        moveTo(s * 0.3f, s * 0.08f)
        lineTo(s * 0.7f, s * 0.08f)
        lineTo(s * 0.6f, s * 0.34f)
        lineTo(s * 0.4f, s * 0.34f)
        close()
    }
    // A lit torch has a solid head.
    if (on) drawPath(head, color) else drawPath(head, color, style = line)
    drawRoundRect(color, Offset(s * 0.4f, s * 0.34f), Size(s * 0.2f, s * 0.58f), CornerRadius(s * 0.06f), style = line)
    drawLine(color, Offset(s * 0.5f, s * 0.5f), Offset(s * 0.5f, s * 0.58f), s * 0.07f, StrokeCap.Round)
}

private fun DrawScope.bell(s: Float, color: Color, line: Stroke, silent: Boolean) {
    val body = Path().apply {
        moveTo(s * 0.18f, s * 0.74f)
        cubicTo(s * 0.3f, s * 0.66f, s * 0.3f, s * 0.55f, s * 0.3f, s * 0.42f)
        cubicTo(s * 0.3f, s * 0.24f, s * 0.4f, s * 0.16f, s * 0.5f, s * 0.16f)
        cubicTo(s * 0.6f, s * 0.16f, s * 0.7f, s * 0.24f, s * 0.7f, s * 0.42f)
        cubicTo(s * 0.7f, s * 0.55f, s * 0.7f, s * 0.66f, s * 0.82f, s * 0.74f)
        close()
    }
    drawPath(body, color, style = line)
    drawArc(color, 0f, 180f, false, Offset(s * 0.42f, s * 0.78f), Size(s * 0.16f, s * 0.12f), style = line)
    if (silent) drawLine(color, Offset(s * 0.15f, s * 0.12f), Offset(s * 0.85f, s * 0.9f), line.width * 1.2f, StrokeCap.Round)
}

private fun DrawScope.vibrate(s: Float, color: Color, line: Stroke) {
    drawRoundRect(color, Offset(s * 0.36f, s * 0.16f), Size(s * 0.28f, s * 0.68f), CornerRadius(s * 0.05f), style = line)
    for (side in listOf(-1f, 1f)) {
        drawLine(color, Offset(s * (0.5f + side * 0.27f), s * 0.36f), Offset(s * (0.5f + side * 0.27f), s * 0.64f), line.width, StrokeCap.Round)
        drawLine(color, Offset(s * (0.5f + side * 0.4f), s * 0.42f), Offset(s * (0.5f + side * 0.4f), s * 0.58f), line.width, StrokeCap.Round)
    }
}

/** A circular arrow around a phone. */
private fun DrawScope.rotate(s: Float, color: Color, line: Stroke) {
    val r = s * 0.4f
    val c = Offset(s * 0.5f, s * 0.5f)
    val start = -60f
    val sweep = 300f
    drawArc(color, start, sweep, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = line)
    // The arrow head sits at the end of the arc and points along the way it was drawn (clockwise).
    val end = (start + sweep) * PI.toFloat() / 180f
    val tip = Offset(c.x + r * cos(end), c.y + r * sin(end))
    val dir = Offset(-sin(end), cos(end))
    val reach = s * 0.16f
    for (turn in listOf(150f, -150f)) {
        val a = turn * PI.toFloat() / 180f
        val back = Offset(dir.x * cos(a) - dir.y * sin(a), dir.x * sin(a) + dir.y * cos(a))
        drawLine(color, tip, Offset(tip.x + back.x * reach, tip.y + back.y * reach), line.width, StrokeCap.Round)
    }
    drawRoundRect(color, Offset(s * 0.4f, s * 0.32f), Size(s * 0.2f, s * 0.36f), CornerRadius(s * 0.04f), style = line)
}

private fun DrawScope.sun(s: Float, color: Color, line: Stroke) {
    val c = Offset(s * 0.5f, s * 0.5f)
    drawCircle(color, s * 0.17f, c, style = line)
    for (i in 0 until 8) {
        val a = i * PI.toFloat() / 4f
        drawLine(
            color,
            Offset(c.x + s * 0.28f * cos(a), c.y + s * 0.28f * sin(a)),
            Offset(c.x + s * 0.4f * cos(a), c.y + s * 0.4f * sin(a)),
            line.width,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.wifi(s: Float, color: Color, line: Stroke) {
    val c = Offset(s * 0.5f, s * 0.82f)
    drawCircle(color, s * 0.055f, c)
    for (r in listOf(s * 0.22f, s * 0.42f, s * 0.62f)) {
        drawArc(color, 225f, 90f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = line)
    }
}

private fun DrawScope.signalBars(s: Float, color: Color) {
    val width = s * 0.12f
    for (i in 0 until 4) {
        val height = s * (0.2f + 0.2f * i)
        drawRect(color, Offset(s * 0.17f + i * s * 0.18f, s * 0.88f - height), Size(width, height))
    }
}

private fun DrawScope.bluetooth(s: Float, color: Color, line: Stroke) {
    val rune = Path().apply {
        moveTo(s * 0.3f, s * 0.3f)
        lineTo(s * 0.7f, s * 0.7f)
        lineTo(s * 0.5f, s * 0.9f)
        lineTo(s * 0.5f, s * 0.1f)
        lineTo(s * 0.7f, s * 0.3f)
        lineTo(s * 0.3f, s * 0.7f)
    }
    drawPath(rune, color, style = line)
}

private fun DrawScope.airplane(s: Float, color: Color) {
    val points = listOf(
        0.5f to 0.06f, 0.58f to 0.34f, 0.94f to 0.6f, 0.94f to 0.7f, 0.58f to 0.58f, 0.56f to 0.8f, 0.7f to 0.88f,
        0.7f to 0.94f, 0.5f to 0.9f, 0.3f to 0.94f, 0.3f to 0.88f, 0.44f to 0.8f, 0.42f to 0.58f, 0.06f to 0.7f,
        0.06f to 0.6f, 0.42f to 0.34f,
    )
    val plane = Path().apply {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(s * x, s * y) else lineTo(s * x, s * y) }
        close()
    }
    drawPath(plane, color)
}

private fun DrawScope.pin(s: Float, color: Color, line: Stroke) {
    val r = s * 0.28f
    val c = Offset(s * 0.5f, s * 0.38f)
    drawCircle(color, r, c, style = line)
    drawCircle(color, s * 0.09f, c)
    drawLine(color, Offset(c.x - r * 0.9f, c.y + r * 0.45f), Offset(s * 0.5f, s * 0.92f), line.width, StrokeCap.Round)
    drawLine(color, Offset(c.x + r * 0.9f, c.y + r * 0.45f), Offset(s * 0.5f, s * 0.92f), line.width, StrokeCap.Round)
}

/** A dot with signal arcs to both sides. */
private fun DrawScope.hotspot(s: Float, color: Color, line: Stroke) {
    val c = Offset(s * 0.5f, s * 0.5f)
    drawCircle(color, s * 0.07f, c)
    for (r in listOf(s * 0.22f, s * 0.4f)) {
        val topLeft = Offset(c.x - r, c.y - r)
        drawArc(color, 135f, 90f, false, topLeft, Size(r * 2, r * 2), style = line)
        drawArc(color, -45f, 90f, false, topLeft, Size(r * 2, r * 2), style = line)
    }
}

/** A battery filled to [percent]. */
private fun DrawScope.battery(s: Float, color: Color, line: Stroke, percent: Int) {
    drawRoundRect(color, Offset(s * 0.1f, s * 0.3f), Size(s * 0.74f, s * 0.4f), CornerRadius(s * 0.05f), style = line)
    drawRect(color, Offset(s * 0.87f, s * 0.43f), Size(s * 0.05f, s * 0.14f))
    val fill = percent.coerceIn(0, 100) / 100f
    if (fill > 0f) drawRect(color, Offset(s * 0.16f, s * 0.36f), Size(s * 0.62f * fill, s * 0.28f))
}
