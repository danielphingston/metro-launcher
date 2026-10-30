package io.github.danielphingston.metro.ui.search

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The hairline drawings for rows that have no app icon of their own. Each is drawn in an 18dp box
 * with the same 1.5dp stroke as the arrow and the magnifier, so the rows read as one family.
 * A new provider adds its glyph here (or builds a [SearchIcon.Glyph] next to its own code).
 */
internal object SearchGlyphs {
    private fun glyph(id: String, draw: DrawScope.(Color) -> Unit) = SearchIcon.Glyph(id, draw)

    private val DrawScope.stroke get() = 1.5.dp.toPx()

    /** Two bars: an answer. */
    val equals = glyph("equals") { color ->
        val w = size.width
        drawLine(color, Offset(w * 0.1f, size.height * 0.35f), Offset(w * 0.9f, size.height * 0.35f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.1f, size.height * 0.65f), Offset(w * 0.9f, size.height * 0.65f), stroke, StrokeCap.Round)
    }

    /** A ring with eight teeth. */
    val gear = glyph("gear") { color ->
        val c = center
        val r = size.minDimension * 0.28f
        drawCircle(color, r, c, style = Stroke(stroke))
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            val from = Offset(c.x + (r + stroke) * cos(a).toFloat(), c.y + (r + stroke) * sin(a).toFloat())
            val to = Offset(c.x + size.minDimension * 0.5f * cos(a).toFloat(), c.y + size.minDimension * 0.5f * sin(a).toFloat())
            drawLine(color, from, to, stroke, StrokeCap.Round)
        }
    }

    /** A clock face; the timer has a button on top. */
    val timer = glyph("timer") { color ->
        val c = Offset(center.x, center.y + size.height * 0.08f)
        val r = size.minDimension * 0.4f
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, c, Offset(c.x, c.y - r * 0.6f), stroke, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + r * 0.4f, c.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x - r * 0.3f, 0f + stroke / 2), Offset(c.x + r * 0.3f, 0f + stroke / 2), stroke, StrokeCap.Round)
    }

    /** A clock face with two bells. */
    val alarm = glyph("alarm") { color ->
        val c = Offset(center.x, center.y + size.height * 0.04f)
        val r = size.minDimension * 0.36f
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, c, Offset(c.x, c.y - r * 0.6f), stroke, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + r * 0.4f, c.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.04f, size.height * 0.2f), Offset(size.width * 0.2f, size.height * 0.04f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.96f, size.height * 0.2f), Offset(size.width * 0.8f, size.height * 0.04f), stroke, StrokeCap.Round)
    }

    /** A page with lines on it. */
    val note = glyph("note") { color ->
        drawRect(color, Offset(size.width * 0.15f, size.height * 0.05f), Size(size.width * 0.7f, size.height * 0.9f), style = Stroke(stroke))
        for (i in 1..3) {
            val y = size.height * (0.2f + i * 0.18f)
            drawLine(color, Offset(size.width * 0.3f, y), Offset(size.width * 0.7f, y), stroke, StrokeCap.Round)
        }
    }

    /** Four tiles. */
    val tiles = glyph("tiles") { color ->
        val s = Size(size.width * 0.38f, size.height * 0.38f)
        for (x in 0..1) for (y in 0..1) {
            drawRect(color, Offset(size.width * (0.08f + x * 0.46f), size.height * (0.08f + y * 0.46f)), s, style = Stroke(stroke))
        }
    }

    /** A picture frame with a sun. */
    val wallpaper = glyph("wallpaper") { color ->
        drawRect(color, Offset(size.width * 0.05f, size.height * 0.15f), Size(size.width * 0.9f, size.height * 0.7f), style = Stroke(stroke))
        drawCircle(color, size.minDimension * 0.1f, Offset(size.width * 0.32f, size.height * 0.4f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.05f, size.height * 0.85f), Offset(size.width * 0.45f, size.height * 0.55f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.45f, size.height * 0.55f), Offset(size.width * 0.95f, size.height * 0.85f), stroke, StrokeCap.Round)
    }

    /** A circled "i". */
    val info = glyph("info") { color ->
        drawCircle(color, size.minDimension * 0.46f, center, style = Stroke(stroke))
        drawLine(color, Offset(center.x, size.height * 0.42f), Offset(center.x, size.height * 0.72f), stroke, StrokeCap.Round)
        drawCircle(color, stroke * 0.6f, Offset(center.x, size.height * 0.28f))
    }

    /** A bin. */
    val bin = glyph("bin") { color ->
        drawLine(color, Offset(size.width * 0.1f, size.height * 0.22f), Offset(size.width * 0.9f, size.height * 0.22f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.35f, size.height * 0.1f), Offset(size.width * 0.65f, size.height * 0.1f), stroke, StrokeCap.Round)
        drawRect(color, Offset(size.width * 0.2f, size.height * 0.22f), Size(size.width * 0.6f, size.height * 0.68f), style = Stroke(stroke))
    }

    /** A circular arrow: recent searches. */
    val history = glyph("history") { color ->
        val c = center
        val r = size.minDimension * 0.42f
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, c, Offset(c.x, c.y - r * 0.6f), stroke, StrokeCap.Round)
        drawLine(color, c, Offset(c.x + r * 0.45f, c.y + r * 0.2f), stroke, StrokeCap.Round)
    }

    /** A handset, for call buttons: the classic receiver outline, drawn from a 24-unit outline. */
    val phone = glyph("phone") { color ->
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(handset, color, style = Stroke(stroke / scale, join = StrokeJoin.Round))
        }
    }

    // A telephone receiver in a 24-unit box, earpiece top left and mouthpiece bottom right.
    private val handset = PathParser().parsePathString(
        "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 " +
            "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 " +
            "-9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 " +
            "0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z",
    ).toPath()

    /** A speech bubble, for message buttons. */
    val message = glyph("message") { color ->
        drawRect(color, Offset(size.width * 0.05f, size.height * 0.1f), Size(size.width * 0.9f, size.height * 0.6f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.7f), Offset(size.width * 0.25f, size.height * 0.92f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.25f, size.height * 0.92f), Offset(size.width * 0.5f, size.height * 0.7f), stroke, StrokeCap.Round)
    }

    /** A magnifier: search in an app. */
    val search = glyph("search") { color ->
        val r = size.minDimension * 0.32f
        val c = Offset(size.width * 0.42f, size.height * 0.42f)
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, Offset(c.x + r * 0.72f, c.y + r * 0.72f), Offset(size.width * 0.92f, size.height * 0.92f), stroke, StrokeCap.Round)
    }

    /** A head and shoulders: a person without a photo. */
    val person = glyph("person") { color ->
        drawCircle(color, size.minDimension * 0.2f, Offset(center.x, size.height * 0.3f), style = Stroke(stroke))
        drawArc(
            color, 180f, 180f, false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.55f),
            size = Size(size.width * 0.76f, size.height * 0.8f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }

    /** A round speech bubble with three dots: a chat, when the chat app's own icon is not available. */
    val chat = glyph("chat") { color ->
        val c = Offset(center.x, size.height * 0.44f)
        val r = size.minDimension * 0.42f
        drawCircle(color, r, c, style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.14f, size.height * 0.96f), Offset(size.width * 0.22f, size.height * 0.72f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.14f, size.height * 0.96f), Offset(size.width * 0.4f, size.height * 0.84f), stroke, StrokeCap.Round)
        for (dx in listOf(-0.45f, 0f, 0.45f)) drawCircle(color, stroke * 0.7f, Offset(c.x + r * dx, c.y))
    }

    /** A calendar page with two rings and a header line. */
    val calendar = glyph("calendar") { color ->
        drawRect(color, Offset(size.width * 0.08f, size.height * 0.18f), Size(size.width * 0.84f, size.height * 0.74f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * 0.08f, size.height * 0.4f), Offset(size.width * 0.92f, size.height * 0.4f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.3f, size.height * 0.05f), Offset(size.width * 0.3f, size.height * 0.28f), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.7f, size.height * 0.05f), Offset(size.width * 0.7f, size.height * 0.28f), stroke, StrokeCap.Round)
        drawCircle(color, stroke * 0.7f, Offset(size.width * 0.34f, size.height * 0.62f))
    }
}
