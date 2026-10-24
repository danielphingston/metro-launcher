package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.github.danielphingston.metro.live.weather.WeatherCondition
import io.github.danielphingston.metro.ui.theme.Metro
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A flat white weather icon drawn on a [Canvas]. Where shapes overlap, the one in front is
 * outlined in the tile colour so they stay distinct without transparency.
 */
@Composable
internal fun WeatherGlyph(condition: WeatherCondition, isDay: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val white = Metro.colors.onAccent
    val gap = Metro.colors.accent
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        when (condition) {
            WeatherCondition.CLEAR ->
                if (isDay) sun(Offset(s * 0.5f, s * 0.5f), s * 0.2f, white) else moon(Offset(s * 0.5f, s * 0.5f), s * 0.32f, white)
            WeatherCondition.PARTLY_CLOUDY -> {
                sky(isDay, Offset(s * 0.36f, s * 0.34f), s * 0.15f, white)
                cloud(s * 0.26f, s * 0.36f, s * 0.7f, white, gap)
            }
            WeatherCondition.CLOUDY -> cloud(s * 0.05f, s * 0.2f, s * 0.9f, white, gap)
            WeatherCondition.FOG -> {
                val lines = listOf(0.12f to 0.88f, 0.22f to 0.78f, 0.12f to 0.88f, 0.28f to 0.72f)
                lines.forEachIndexed { i, (from, to) ->
                    val y = s * (0.28f + 0.17f * i)
                    drawLine(white, Offset(s * from, y), Offset(s * to, y), s * 0.07f, StrokeCap.Round)
                }
            }
            WeatherCondition.DRIZZLE -> {
                cloud(s * 0.07f, s * 0.1f, s * 0.86f, white, gap)
                drops(s, white, top = 0.74f, length = 0.08f)
            }
            WeatherCondition.RAIN -> {
                cloud(s * 0.07f, s * 0.1f, s * 0.86f, white, gap)
                drops(s, white, top = 0.72f, length = 0.2f)
            }
            WeatherCondition.SHOWERS -> {
                sky(isDay, Offset(s * 0.3f, s * 0.28f), s * 0.13f, white)
                cloud(s * 0.26f, s * 0.24f, s * 0.68f, white, gap)
                drops(s, white, top = 0.72f, length = 0.2f)
            }
            WeatherCondition.SNOW -> snowflake(Offset(s * 0.5f, s * 0.5f), s * 0.42f, white)
            WeatherCondition.THUNDERSTORM -> {
                cloud(s * 0.07f, s * 0.06f, s * 0.86f, white, gap)
                bolt(s, white, gap)
            }
        }
    }
}

private fun DrawScope.sky(isDay: Boolean, center: Offset, radius: Float, color: Color) =
    if (isDay) sun(center, radius, color) else moon(center, radius * 1.2f, color)

/** A disc with eight rays. */
private fun DrawScope.sun(center: Offset, radius: Float, color: Color) {
    drawCircle(color, radius, center)
    repeat(8) { i ->
        val angle = i * PI.toFloat() / 4
        val direction = Offset(cos(angle), sin(angle))
        drawLine(color, center + direction * (radius * 1.5f), center + direction * (radius * 2f), radius * 0.35f, StrokeCap.Round)
    }
}

/** A crescent: a disc with a bite taken out of its upper right. */
private fun DrawScope.moon(center: Offset, radius: Float, color: Color) {
    val disc = Path().apply { addOval(Rect(center, radius)) }
    val bite = Path().apply { addOval(Rect(center + Offset(radius * 0.5f, -radius * 0.35f), radius * 0.85f)) }
    drawPath(Path.combine(PathOperation.Difference, disc, bite), color)
}

/** A cloud [w] wide, [grow] bigger on every side, so the same call can draw its outline. */
private fun DrawScope.cloudShape(left: Float, top: Float, w: Float, color: Color, grow: Float) {
    drawRoundRect(
        color,
        Offset(left - grow, top + w * 0.3f - grow),
        Size(w + grow * 2, w * 0.3f + grow * 2),
        CornerRadius(w * 0.15f + grow),
    )
    drawCircle(color, w * 0.2f + grow, Offset(left + w * 0.3f, top + w * 0.32f))
    drawCircle(color, w * 0.25f + grow, Offset(left + w * 0.58f, top + w * 0.25f))
}

private fun DrawScope.cloud(left: Float, top: Float, width: Float, color: Color, gap: Color) {
    cloudShape(left, top, width, gap, grow = width * 0.045f)
    cloudShape(left, top, width, color, grow = 0f)
}

/** Three slanted rain drops, [length] long as a fraction of the glyph, starting [top] down. */
private fun DrawScope.drops(s: Float, color: Color, top: Float, length: Float) {
    listOf(0.34f, 0.52f, 0.7f).forEach { x ->
        drawLine(
            color,
            Offset(s * (x + 0.04f), s * top),
            Offset(s * (x + 0.04f - length * 0.4f), s * (top + length)),
            s * 0.06f,
            StrokeCap.Round,
        )
    }
}

private fun DrawScope.snowflake(center: Offset, radius: Float, color: Color) {
    val stroke = radius * 0.15f
    repeat(6) { i ->
        val angle = i * PI.toFloat() / 3
        val arm = Offset(cos(angle), sin(angle))
        drawLine(color, center, center + arm * radius, stroke, StrokeCap.Round)
        val branchStart = center + arm * (radius * 0.55f)
        listOf(-1, 1).forEach { side ->
            val branch = angle + side * PI.toFloat() / 4
            drawLine(color, branchStart, branchStart + Offset(cos(branch), sin(branch)) * (radius * 0.27f), stroke, StrokeCap.Round)
        }
    }
}

private fun DrawScope.bolt(s: Float, color: Color, gap: Color) {
    val path = Path().apply {
        moveTo(s * 0.58f, s * 0.5f)
        lineTo(s * 0.38f, s * 0.76f)
        lineTo(s * 0.5f, s * 0.76f)
        lineTo(s * 0.44f, s * 0.98f)
        lineTo(s * 0.68f, s * 0.68f)
        lineTo(s * 0.55f, s * 0.68f)
        lineTo(s * 0.64f, s * 0.5f)
        close()
    }
    drawPath(path, gap, style = Stroke(s * 0.07f, join = StrokeJoin.Round))
    drawPath(path, color)
}
