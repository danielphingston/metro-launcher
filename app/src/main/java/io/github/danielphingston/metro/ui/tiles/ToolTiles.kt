package io.github.danielphingston.metro.ui.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.danielphingston.metro.data.Countdowns
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.RoutineTap
import io.github.danielphingston.metro.data.Routines
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.ResolvedTile
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.LocalMotion
import io.github.danielphingston.metro.ui.start.CustomTileIcon
import io.github.danielphingston.metro.ui.start.TileLabel
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.start.Turnstile
import io.github.danielphingston.metro.ui.theme.LocalTileDecor
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import io.github.danielphingston.metro.ui.theme.tileSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What tool tiles keep in memory only: the last coin toss and dice throw (nothing is written for
 * them), the countdown's chosen view, and the routine tile's short "tap again to undo" window.
 */
internal object ToolMemory {
    data class Toss(val heads: Boolean, val serial: Int, val history: List<Boolean>)

    data class Roll(val values: List<Int>, val serial: Int)

    /** A routine tile's line for a moment after a tap or double tap. */
    data class Hint(val text: String, val at: Long, val until: Long)

    val tosses = mutableStateMapOf<Long, Toss>()
    val rolls = mutableStateMapOf<Long, Roll>()
    val hints = mutableStateMapOf<Long, Hint>()
    val countdownViews = mutableStateMapOf<Long, Int>()

    fun toss(id: Long) {
        val last = tosses[id]
        val heads = Random.nextBoolean()
        tosses[id] = Toss(heads, (last?.serial ?: 0) + 1, ((last?.history ?: emptyList()) + heads).takeLast(HISTORY))
    }

    fun roll(id: Long, count: Int, sides: Int) {
        rolls[id] = Roll(List(count) { 1 + Random.nextInt(sides) }, (rolls[id]?.serial ?: 0) + 1)
    }

    const val HISTORY = 10
}

/**
 * What a tap on a tool tile does; false for other tiles. Notes open in place (the caller handles
 * them like a folder). [update] changes the stored tile without an undo step: ticks are not edits.
 * A routine also has a double tap ([undo]); only its taps wait the double-tap timeout.
 */
internal object ToolTaps {
    fun activate(view: View, id: Long, content: TileContent, update: (Long, (TileContent.Tool) -> TileContent.Tool) -> Unit): Boolean {
        when (content) {
            is TileContent.Routine -> routine(view, id, update) { r, now, zone -> RoutineTap.tap(r, now, zone) }
            is TileContent.Coin -> ToolMemory.toss(id)
            is TileContent.Dice -> ToolMemory.roll(id, content.count, content.sides)
            is TileContent.Countdown -> ToolMemory.countdownViews[id] = ((ToolMemory.countdownViews[id] ?: 0) + 1) % 3
            else -> return false
        }
        return true
    }

    /** A double tap on a routine: takes back today's last tick. */
    fun undo(view: View, id: Long, update: (Long, (TileContent.Tool) -> TileContent.Tool) -> Unit) =
        routine(view, id, update) { r, now, zone -> RoutineTap.undo(r, now, zone) }

    /** Whether tile content [content] has a double-tap action (and so waits a moment before a single tap counts). */
    fun hasDoubleTap(content: TileContent) = content is TileContent.Routine

    private fun routine(
        view: View,
        id: Long,
        update: (Long, (TileContent.Tool) -> TileContent.Tool) -> Unit,
        act: (TileContent.Routine, Long, ZoneId) -> RoutineTap.Outcome,
    ) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        var outcome: RoutineTap.Outcome? = null
        update(id) { c ->
            val r = c as? TileContent.Routine ?: return@update c
            val o = act(r, now, zone)
            outcome = o
            (o as? RoutineTap.Outcome.Changed)?.routine ?: r
        }
        val o = outcome ?: return
        val changed = o is RoutineTap.Outcome.Changed
        ToolMemory.hints[id] = ToolMemory.Hint(o.hint, now, now + if (changed) Routines.HINT_MILLIS else 2_000L)
        view.performHapticFeedback(if (changed) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK)
    }
}

/**
 * The time a tool tile draws for: read when start is shown, then again only when [nextChange]
 * says the face changes by itself (a dose falls due, midnight). Nothing runs while start is not
 * shown, so a hidden launcher costs nothing.
 */
@Composable
internal fun rememberToolNow(key: Any?, nextChange: (Long) -> Long): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = LocalToolClock.current
    var now by remember { mutableLongStateOf(clock()) }
    val next by rememberUpdatedState(nextChange)
    LaunchedEffect(lifecycle, key) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock()
                // Just past the change, and never longer than a few hours (the clock may be changed).
                delay((next(now) - now).coerceIn(1_000L, 6 * 3_600_000L) + 250L)
            }
        }
    }
    return now
}

/** The time tool tiles read; tests fix it so their faces are the same on every run. */
internal val LocalToolClock = androidx.compose.runtime.staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** A tool tile's face, by its kind: the one place that maps a tool to how it is drawn. */
@Composable
internal fun ToolFace(tile: ResolvedTile, m: TileMetrics, icons: IconCache, open: Boolean) {
    when (tile.spec.content as? TileContent.Tool ?: return) {
        is TileContent.Routine -> RoutineTile(tile, m, icons)
        is TileContent.Countdown -> CountdownTile(tile, m)
        is TileContent.Coin -> CoinTile(tile, m)
        is TileContent.Dice -> DiceTile(tile, m)
        is TileContent.Notes -> NotesTile(tile, m, open)
    }
}

/** Whether tool animations play: off with the system's animations, like the launch animation. */
@Composable
private fun animationsOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Turnstile.animationsEnabled(resolver) }
}

/** Dims a tile that is done or not due yet, so the ones that need doing stand out. */
@Composable
private fun BoxScope.Quiet(quiet: Boolean) {
    if (quiet && LocalTileDecor.current.background) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)))
}

@Composable
private fun white() = Metro.colors.onAccent

/** A thin bar along the bottom edge, filled to [fraction]. */
@Composable
private fun BoxScope.BottomBar(fraction: Float, m: TileMetrics) {
    val color = white()
    Canvas(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(m.dp(3f))) {
        drawRect(color.copy(alpha = 0.25f))
        drawRect(color, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height))
    }
}

/** The routine tile; see [Routines] for what it says and [RoutineTap] for what a tap does. */
@Composable
internal fun RoutineTile(tile: ResolvedTile, m: TileMetrics, icons: IconCache) {
    val r = tile.spec.content as? TileContent.Routine ?: return
    val context = LocalContext.current
    val id = tile.spec.id
    val zone = ZoneId.systemDefault()
    val ticked = rememberToolNow(r) { Routines.nextChange(r, it, zone) }
    val hint = ToolMemory.hints[id]
    LaunchedEffect(hint) {
        if (hint == null) return@LaunchedEffect
        delay((hint.until - System.currentTimeMillis()).coerceAtLeast(0))
        if (ToolMemory.hints[id] == hint) ToolMemory.hints.remove(id)
    }
    val now = maxOf(ticked, hint?.at ?: 0L)
    val is24 = android.text.format.DateFormat.is24HourFormat(context)
    val s = Routines.status(r, now, zone, is24)
    val size = tile.spec.size
    val ink = white()
    val detail = hint?.text ?: s.detail
    Box(Modifier.fillMaxSize().tileSurface().semantics { stateDescription = "${s.headline}, $detail" }) {
        Quiet(!s.due)
        val mark = when {
            s.late > 0 -> "!"
            s.complete -> "✓"
            s.slots.size > 1 && s.slots.any { it.done } -> "${s.slots.count { it.done }}/${s.slots.size}"
            else -> null
        }
        when (size) {
            TileSize.SMALL -> {
                RoutineIcon(tile.spec.icon, m.dp(40f), icons, Modifier.align(Alignment.Center))
                mark?.let { MetroText(it, MetroType.style(m.text(13f), FontWeight.SemiBold), color = ink, modifier = Modifier.align(Alignment.TopEnd).padding(m.dp(5f))) }
            }
            TileSize.MEDIUM -> Column(Modifier.padding(m.dp(8f))) {
                RoutineIcon(tile.spec.icon, m.dp(28f), icons)
                Spacer(Modifier.height(m.dp(6f)))
                MetroText(s.headline, MetroType.style(m.text(24f), FontWeight.Light), color = ink, maxLines = 1)
                MetroText(detail, MetroType.style(m.text(13f)), color = ink, maxLines = 1)
                if (s.slots.size > 1) {
                    Spacer(Modifier.height(m.dp(6f)))
                    Doses(s.slots, m, labels = null)
                }
            }
            TileSize.WIDE -> Row(Modifier.padding(m.dp(8f)), horizontalArrangement = Arrangement.spacedBy(m.dp(12f))) {
                RoutineIcon(tile.spec.icon, m.dp(44f), icons)
                Column(Modifier.weight(1f)) {
                    MetroText(s.headline, MetroType.style(m.text(28f), FontWeight.Light), color = ink, maxLines = 1)
                    MetroText(detail, MetroType.style(m.text(13f)), color = ink, maxLines = 1)
                    Spacer(Modifier.height(m.dp(6f)))
                    if (s.slots.size > 1) Doses(s.slots, m, labels = s.slots.map { Routines.timeLabel(it.minutes, is24) })
                }
            }
            TileSize.LARGE -> Column(Modifier.padding(m.dp(10f))) {
                RoutineIcon(tile.spec.icon, m.dp(56f), icons)
                Spacer(Modifier.height(m.dp(10f)))
                MetroText(s.headline, MetroType.style(m.text(40f), FontWeight.Light), color = ink, maxLines = 1)
                MetroText(detail, MetroType.style(m.text(15f)), color = ink, maxLines = 1)
                if (s.slots.isNotEmpty()) {
                    Spacer(Modifier.height(m.dp(10f)))
                    Doses(s.slots, m, labels = s.slots.map { Routines.timeLabel(it.minutes, is24) })
                }
                Spacer(Modifier.height(m.dp(14f)))
                if (r.daily) {
                    MetroText("last 14 days", MetroType.style(m.text(12f), FontWeight.SemiBold), color = ink.copy(alpha = 0.8f))
                    Spacer(Modifier.height(m.dp(4f)))
                    val days = Routines.history(r, now, zone, 14)
                    HistoryStrip(days.take(7), m)
                    Spacer(Modifier.height(m.dp(4f)))
                    HistoryStrip(days.drop(7), m)
                } else if (r.marks.isNotEmpty()) {
                    MetroText("last done", MetroType.style(m.text(12f), FontWeight.SemiBold), color = ink.copy(alpha = 0.8f))
                    Spacer(Modifier.height(m.dp(4f)))
                    r.marks.sortedDescending().take(4).forEach {
                        MetroText(Countdowns.dateLabel(java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toEpochDay()), MetroType.style(m.text(14f)), color = ink, maxLines = 1)
                    }
                }
            }
        }
        if (size == TileSize.WIDE && r.daily) {
            HistoryStrip(Routines.history(r, now, zone, 7), m, Modifier.align(Alignment.BottomEnd).padding(end = m.dp(8f), bottom = m.dp(8f)))
        }
        if (size != TileSize.SMALL) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
        s.progress?.let { if (!r.daily) BottomBar(it, m) }
    }
}

/** The routine's picture: the chosen emoji or icon, else a tick in a circle. */
@Composable
private fun RoutineIcon(icon: String?, size: Dp, icons: IconCache, modifier: Modifier = Modifier) {
    if (icon != null) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) { CustomTileIcon(icon, size, icons) }
        return
    }
    val color = white()
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.06f
        drawCircle(color, s * 0.42f, center, style = Stroke(w))
        drawLine(color, Offset(s * 0.3f, s * 0.52f), Offset(s * 0.45f, s * 0.66f), w, StrokeCap.Round)
        drawLine(color, Offset(s * 0.45f, s * 0.66f), Offset(s * 0.72f, s * 0.36f), w, StrokeCap.Round)
    }
}

/** One mark per dose: a filled circle when done, a ring when due, a faint ring when later; with [labels] under them. */
@Composable
private fun Doses(slots: List<Routines.Slot>, m: TileMetrics, labels: List<String>?) {
    val ink = white()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (labels != null) m.dp(10f) else m.dp(5f)), verticalArrangement = Arrangement.spacedBy(m.dp(4f))) {
        slots.forEachIndexed { i, slot ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.size(m.dp(13f))) {
                    val r = size.minDimension / 2
                    when {
                        slot.done -> drawCircle(ink, r)
                        slot.due -> drawCircle(ink, r - 1.dp.toPx(), style = Stroke(2.dp.toPx()))
                        else -> drawCircle(ink.copy(alpha = 0.5f), r - 1.dp.toPx(), style = Stroke(1.2.dp.toPx()))
                    }
                }
                labels?.getOrNull(i)?.let { MetroText(it, MetroType.style(m.text(11f)), color = ink.copy(alpha = if (slot.done) 0.7f else 1f), maxLines = 1) }
            }
        }
    }
}

/** A row of day squares, oldest first (today last): filled when done, faint when partly, an outline when not. */
@Composable
private fun HistoryStrip(days: List<Float>, m: TileMetrics, modifier: Modifier = Modifier) {
    val ink = white()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
        days.forEach { done ->
            Canvas(Modifier.size(m.dp(11f))) {
                val stroke = 1.5.dp.toPx()
                drawRect(ink.copy(alpha = 0.8f), topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                // Done: filled. Partly done (some of the day's doses): half strength.
                if (done >= 1f) drawRect(ink) else if (done > 0f) drawRect(ink.copy(alpha = 0.45f))
            }
        }
    }
}

/** Days to a date, or since it; a tap turns between days, weeks and the date. */
@Composable
internal fun CountdownTile(tile: ResolvedTile, m: TileMetrics) {
    val c = tile.spec.content as? TileContent.Countdown ?: return
    val zone = ZoneId.systemDefault()
    val now = rememberToolNow(c) { Countdowns.nextChange(it, zone) }
    val view = ToolMemory.countdownViews[tile.spec.id] ?: 0
    val days = Countdowns.daysLeft(c.epochDay, now, zone)
    val ink = white()
    FlipIn(view) { shown ->
        val (big, words) = Countdowns.face(c.epochDay, now, zone, shown)
        val scale = when {
            big.length > 5 -> 0.5f
            big.length > 3 -> 0.62f
            else -> 1f
        }
        Box(Modifier.fillMaxSize().tileSurface().semantics { stateDescription = "$big $words".trim() }) {
            Quiet(days < 0)
            when (tile.spec.size) {
                TileSize.SMALL -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    MetroText(big, MetroType.style(m.text(if (big.length > 3) 18f else 30f), FontWeight.Light), color = ink, maxLines = 1)
                    if (words.isNotEmpty()) MetroText(if (days < 0) "ago" else words.substringBefore(' '), MetroType.style(m.text(11f)), color = ink, maxLines = 1)
                }
                TileSize.MEDIUM -> Column(Modifier.padding(m.dp(8f))) {
                    MetroText(big, MetroType.style(m.text(56f * scale), FontWeight.Light), color = ink, maxLines = 1)
                    MetroText(words, MetroType.style(m.text(13f)), color = ink, maxLines = 1)
                }
                TileSize.WIDE -> Row(Modifier.padding(m.dp(8f)).fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        MetroText(big, MetroType.style(m.text(64f * scale), FontWeight.Light), color = ink, maxLines = 1)
                        MetroText(words, MetroType.style(m.text(13f)), color = ink, maxLines = 1)
                    }
                    Spacer(Modifier.width(m.dp(8f)))
                    // The name keeps to its half, wrapping to two lines, so it never runs into the number.
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        MetroText(tile.label, MetroType.style(m.text(20f), FontWeight.Light).copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), color = ink, maxLines = 2)
                        MetroText(Countdowns.dateLabel(c.epochDay), MetroType.style(m.text(13f)), color = ink, maxLines = 1)
                    }
                }
                TileSize.LARGE -> Column(Modifier.padding(m.dp(10f)).fillMaxHeight()) {
                    MetroText(tile.label, MetroType.style(m.text(26f), FontWeight.Light), color = ink, maxLines = 2)
                    Spacer(Modifier.weight(1f))
                    MetroText(big, MetroType.style(m.text(120f * scale), FontWeight.Light), color = ink, maxLines = 1)
                    MetroText(words, MetroType.style(m.text(18f)), color = ink, maxLines = 1)
                    MetroText(Countdowns.dateLabel(c.epochDay), MetroType.style(m.text(13f)), color = ink.copy(alpha = 0.85f), maxLines = 1)
                    Spacer(Modifier.height(m.dp(14f)))
                }
            }
            if (tile.spec.size == TileSize.MEDIUM) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
            if (c.created > 0 && days > 0) {
                val start = c.created
                val end = Countdowns.nextChange(now, zone) + (days - 1) * 86_400_000L
                BottomBar(((now - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f), m)
            }
        }
    }
}

/** Shows [content] for [key]; when the key changes the new face flips in, like a live tile turning over. */
@Composable
private fun FlipIn(key: Int, content: @Composable (Int) -> Unit) {
    val angle = remember { Animatable(0f) }
    val animate = animationsOn()
    val motion = LocalMotion.current
    var shown by remember { mutableIntStateOf(key) }
    LaunchedEffect(key) {
        if (key == shown) return@LaunchedEffect
        if (animate) angle.animateTo(-90f, tween(motion.duration(130)))
        shown = key
        if (animate) {
            angle.snapTo(90f)
            angle.animateTo(0f, tween(motion.duration(200), easing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f)))
        }
    }
    Box(Modifier.fillMaxSize().graphicsLayer { rotationX = angle.value; cameraDistance = 12f * density }) { content(shown) }
}

/**
 * Heads or tails. A toss throws the coin up, spinning end over end and slowing down, and lands on
 * the new side; its face changes each half turn, as a real coin's would.
 */
@Composable
internal fun CoinTile(tile: ResolvedTile, m: TileMetrics) {
    val id = tile.spec.id
    val toss = ToolMemory.tosses[id]
    val animate = animationsOn()
    val motion = LocalMotion.current
    val turn = remember { Animatable(0f) }
    var settledHeads by remember { mutableStateOf(toss?.heads ?: true) }
    var settledSerial by remember { mutableIntStateOf(toss?.serial ?: 0) }
    var startHeads by remember { mutableStateOf(settledHeads) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(toss?.serial) {
        val t = toss ?: return@LaunchedEffect
        if (t.serial == settledSerial) return@LaunchedEffect
        settledSerial = t.serial
        if (animate) {
            startHeads = settledHeads
            // Whole half turns, an even number when it lands on the side it started from.
            var halves = 9 + Random.nextInt(3)
            if ((halves % 2 == 0) != (t.heads == startHeads)) halves += 1
            turn.snapTo(0f)
            turn.animateTo(halves * 180f, tween(motion.duration(1_150), easing = CubicBezierEasing(0.12f, 0.7f, 0.3f, 1f)))
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        settledHeads = t.heads
        turn.snapTo(0f)
    }
    val angle = turn.value
    val halfTurns = floor((angle + 90f) / 180f).toInt()
    val faceHeads = if (angle == 0f) settledHeads else if (halfTurns % 2 == 0) startHeads else !startHeads
    val total = if (turn.targetValue > 0f) turn.targetValue else 1f
    val progress = (angle / total).coerceIn(0f, 1f)
    val lift = sin(PI * progress).toFloat()
    val ink = white()
    val size = tile.spec.size
    val coin = when (size) {
        TileSize.SMALL -> m.dp(46f)
        TileSize.MEDIUM -> m.dp(64f)
        TileSize.WIDE -> m.dp(88f)
        TileSize.LARGE -> m.dp(150f)
    }
    Box(Modifier.fillMaxSize().tileSurface().semantics { stateDescription = if (toss == null) "not tossed yet" else if (toss.heads) "heads" else "tails" }) {
        val coinBox = @Composable { modifier: Modifier ->
            Box(
                modifier
                    .size(coin)
                    .graphicsLayer {
                        // Each half turn shows the other side the right way up.
                        rotationX = ((angle + 90f) % 180f) - 90f
                        cameraDistance = 14f * density
                        val grow = 1f + 0.28f * lift
                        scaleX = grow
                        scaleY = grow
                        translationY = -lift * coin.toPx() * 0.35f
                    },
                contentAlignment = Alignment.Center,
            ) {
                CoinFace(faceHeads, coin, ink)
                // Edge-on, the coin darkens a little.
                val edge = abs(sin(angle * PI / 180.0)).toFloat()
                if (edge > 0.01f) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f * edge), CircleShape))
            }
        }
        val history = toss?.history.orEmpty().let { if (turn.isRunning) it.dropLast(1) else it }
        when (size) {
            TileSize.SMALL -> coinBox(Modifier.align(Alignment.Center))
            TileSize.MEDIUM -> {
                coinBox(Modifier.align(Alignment.Center).padding(bottom = m.dp(14f)))
                MetroText(
                    if (turn.isRunning) "" else if (toss == null) "tap to toss" else if (settledHeads) "heads" else "tails",
                    MetroType.style(m.text(14f)), color = ink,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = m.dp(8f), bottom = m.dp(6f)),
                )
            }
            TileSize.LARGE -> Column(Modifier.align(Alignment.Center).padding(bottom = m.dp(16f)), horizontalAlignment = Alignment.CenterHorizontally) {
                coinBox(Modifier)
                Spacer(Modifier.height(m.dp(24f)))
                MetroText(
                    if (turn.isRunning) " " else if (toss == null) "tap to toss" else if (settledHeads) "heads" else "tails",
                    MetroType.style(m.text(40f), FontWeight.Light), color = ink, maxLines = 1,
                )
                if (history.isNotEmpty()) {
                    Spacer(Modifier.height(m.dp(8f)))
                    TossHistory(history, m)
                    val heads = history.count { it }
                    MetroText("last ${history.size}: $heads heads · ${history.size - heads} tails", MetroType.style(m.text(13f)), color = ink.copy(alpha = 0.85f))
                }
            }
            TileSize.WIDE -> Row(Modifier.align(Alignment.CenterStart).padding(start = m.dp(16f)), verticalAlignment = Alignment.CenterVertically) {
                coinBox(Modifier)
                Spacer(Modifier.width(m.dp(20f)))
                Column {
                    MetroText(
                        if (turn.isRunning) " " else if (toss == null) "tap to toss" else if (settledHeads) "heads" else "tails",
                        MetroType.style(m.text(34f), FontWeight.Light), color = ink, maxLines = 1,
                    )
                    if (history.isNotEmpty()) {
                        Spacer(Modifier.height(m.dp(6f)))
                        TossHistory(history, m)
                        val heads = history.count { it }
                        MetroText("last ${history.size}: $heads heads · ${history.size - heads} tails", MetroType.style(m.text(12f)), color = ink.copy(alpha = 0.85f))
                    }
                }
            }
        }
        if (size != TileSize.SMALL) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
    }
}

/** One side of the coin: a rim, an inner ring and H or T. */
@Composable
private fun CoinFace(heads: Boolean, size: Dp, ink: Color) {
    Box(Modifier.size(size).border(2.dp, ink, CircleShape).padding(size * 0.1f).border(1.dp, ink.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
        MetroText(if (heads) "H" else "T", MetroType.style(with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.38f).toSp() }, FontWeight.Light), color = ink)
    }
}

@Composable
private fun TossHistory(history: List<Boolean>, m: TileMetrics) {
    val ink = white()
    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(4f))) {
        history.takeLast(ToolMemory.HISTORY).forEach { heads ->
            Canvas(Modifier.size(m.dp(9f))) {
                if (heads) drawCircle(ink) else drawCircle(ink, size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * One to three dice. A throw tumbles them: each spins down to rest with a hop and a small
 * bounce, its face flickering through numbers that change more slowly as it settles, and the
 * dice land one after another.
 */
@Composable
internal fun DiceTile(tile: ResolvedTile, m: TileMetrics) {
    val dice = tile.spec.content as? TileContent.Dice ?: return
    val id = tile.spec.id
    val roll = ToolMemory.rolls[id]
    val animate = animationsOn()
    val motion = LocalMotion.current
    val progress = remember { Animatable(1f) }
    var settledSerial by remember { mutableIntStateOf(roll?.serial ?: 0) }
    val spins = remember { mutableStateOf(List(3) { 0f }) }
    val view = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(roll?.serial) {
        val r = roll ?: return@LaunchedEffect
        if (r.serial == settledSerial) return@LaunchedEffect
        settledSerial = r.serial
        if (!animate) return@LaunchedEffect
        spins.value = List(3) { (if (Random.nextBoolean()) 1 else -1) * (360f + Random.nextFloat() * 360f) }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(motion.duration(950), easing = LinearEasing))
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
    val values = roll?.values ?: List(dice.count) { 6 }
    val p = progress.value
    val size = tile.spec.size
    val shown = if (size == TileSize.SMALL) 1 else dice.count
    val dieSize = when (size) {
        TileSize.SMALL -> m.dp(44f)
        TileSize.MEDIUM -> if (shown == 1) m.dp(64f) else if (shown == 2) m.dp(56f) else m.dp(46f)
        TileSize.WIDE -> m.dp(64f)
        TileSize.LARGE -> if (shown == 3) m.dp(84f) else m.dp(100f)
    }
    val ink = white()
    Box(Modifier.fillMaxSize().tileSurface().clipToBounds().semantics { stateDescription = if (dice.count > 1) "${values.joinToString(", ")}, total ${values.sum()}" else "${values.first()}" }) {
        Row(
            Modifier.align(
                when (size) {
                    TileSize.WIDE -> Alignment.CenterStart
                    TileSize.LARGE -> Alignment.TopCenter
                    else -> Alignment.Center
                },
            ).padding(start = if (size == TileSize.WIDE) m.dp(16f) else 0.dp, top = if (size == TileSize.LARGE) m.dp(96f) else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(m.dp(10f)),
        ) {
            for (i in 0 until shown.coerceAtMost(values.size)) {
                // Each die lands a little after the one before it.
                val local = ((p - i * 0.08f) / (1f - (shown - 1) * 0.08f)).coerceIn(0f, 1f)
                val settle = 1f - (1f - local) * (1f - local) * (1f - local)
                val face = if (local >= 0.78f || p >= 1f) values[i] else flicker(settledSerial, i, local, dice.sides)
                val hop = if (local < 0.7f) sin(PI * local / 0.7f).toFloat() else 0.22f * sin(PI * (local - 0.7f) / 0.3f).toFloat()
                val squash = if (local in 0.66f..0.76f) 1f - 0.12f * sin(PI * (local - 0.66f) / 0.1f).toFloat() else 1f
                Box(
                    Modifier.size(dieSize).graphicsLayer {
                        rotationZ = spins.value[i] * (1f - settle)
                        translationY = -hop * dieSize.toPx() * 0.45f
                        scaleY = squash
                        scaleX = 2f - squash
                    },
                ) { DieFace(face, dice.sides, ink) }
            }
        }
        if (size == TileSize.WIDE || size == TileSize.LARGE) {
            val total = values.take(shown).sum()
            Column(
                if (size == TileSize.WIDE) Modifier.align(Alignment.CenterEnd).padding(end = m.dp(16f)) else Modifier.align(Alignment.BottomCenter).padding(bottom = m.dp(64f)),
                horizontalAlignment = if (size == TileSize.WIDE) Alignment.End else Alignment.CenterHorizontally,
            ) {
                MetroText(if (p < 1f) " " else "$total", MetroType.style(m.text(56f), FontWeight.Light), color = ink)
                if (shown > 1) MetroText("total", MetroType.style(m.text(13f)), color = ink)
            }
        }
        if (size != TileSize.SMALL) TileLabel(if (dice.count > 1) "${dice.count}d${dice.sides}" else "d${dice.sides}", m, Modifier.align(Alignment.BottomStart))
    }
}

/** A tumbling die's face: a new number each step, the steps slowing down as it settles. */
private fun flicker(serial: Int, die: Int, progress: Float, sides: Int): Int {
    val step = (sqrt(progress) * 12).toInt()
    return 1 + Random(serial * 131 + die * 17 + step).nextInt(sides)
}

/** A die: a rounded square with pips (six sides) or its number (twenty). */
@Composable
private fun DieFace(value: Int, sides: Int, ink: Color) {
    if (sides != 6) {
        Box(Modifier.fillMaxSize().border(2.dp, ink, RoundedCornerShape(18)), contentAlignment = Alignment.Center) {
            MetroText("$value", MetroType.style(with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toSp() }, FontWeight.Light), color = ink)
        }
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        val s = size.minDimension
        val stroke = 2.dp.toPx()
        drawRoundRect(ink, Offset(stroke / 2, stroke / 2), Size(s - stroke, s - stroke), CornerRadius(s * 0.16f), style = Stroke(stroke))
        val r = s * 0.085f
        val a = s * 0.27f
        val b = s * 0.5f
        val c = s * 0.73f
        val spots = when (value) {
            1 -> listOf(b to b)
            2 -> listOf(a to a, c to c)
            3 -> listOf(a to a, b to b, c to c)
            4 -> listOf(a to a, c to a, a to c, c to c)
            5 -> listOf(a to a, c to a, b to b, a to c, c to c)
            else -> listOf(a to a, c to a, a to b, c to b, a to c, c to c)
        }
        spots.forEach { (x, y) -> drawCircle(ink, r, Offset(x, y)) }
    }
}

/** The notes tile: a checklist, a note or handwriting; a tap opens it in place (see NotesPanel). */
@Composable
internal fun NotesTile(tile: ResolvedTile, m: TileMetrics, open: Boolean) {
    val n = tile.spec.content as? TileContent.Notes ?: return
    val ink = white()
    val size = tile.spec.size
    val left = n.items.count { !it.done }
    val state = when (n.style) {
        NoteStyle.NOTE -> listOfNotNull(
            n.text.takeIf { it.isNotBlank() }?.take(200),
            if (n.items.isEmpty()) null else if (left == 0) "all ticked" else "$left of ${n.items.size} left",
        ).joinToString(", ").ifEmpty { "empty" }
        NoteStyle.INK -> "handwriting"
    }
    Box(Modifier.fillMaxSize().tileSurface().semantics { stateDescription = state }) {
        when (n.style) {
            NoteStyle.NOTE -> {
                Quiet(n.text.isBlank() && n.items.isNotEmpty() && left == 0)
                if (size == TileSize.SMALL) {
                    ToolGlyph(if (n.items.isEmpty() && n.text.isNotBlank()) ToolGlyphKind.TEXT else ToolGlyphKind.LIST, m.dp(38f), Modifier.align(Alignment.Center))
                    val mark = if (n.items.isNotEmpty() && left == 0) "✓" else if (left > 0) "$left" else ""
                    MetroText(mark, MetroType.style(m.text(13f), FontWeight.SemiBold), color = ink, modifier = Modifier.align(Alignment.TopEnd).padding(m.dp(5f)))
                } else {
                    NoteFace(n, tile.label, size, left, m)
                }
            }
            NoteStyle.INK -> InkFace(tile.spec.id, n, size, m)
        }
        if (size != TileSize.SMALL && n.style == NoteStyle.INK) TileLabel(tile.label, m, Modifier.align(Alignment.BottomStart))
        if (open) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(m.dp(3f)).background(ink))
    }
}

/**
 * A notes tile on start: its name and how many items are left, the first lines of its text, then
 * as many unticked items as fit under them, ticked ones last, and "+n more" for the rest.
 */
@Composable
private fun NoteFace(n: TileContent.Notes, label: String, size: TileSize, left: Int, m: TileMetrics) {
    val ink = white()
    // Rows of 13sp the tile has under its name: text lines come out of the same room as items.
    val rows = when (size) {
        TileSize.MEDIUM -> 5
        TileSize.WIDE -> 4
        else -> 14
    }
    val hasText = n.text.isNotBlank()
    val textLines = if (!hasText) 0 else if (n.items.isEmpty()) rows else minOf(if (size == TileSize.LARGE) 4 else 2, rows - 1)
    val ordered = n.items.filter { !it.done } + n.items.filter { it.done }
    // Wide tiles list items in two columns.
    val columns = if (size == TileSize.WIDE) 2 else 1
    val shown = ordered.take((rows - textLines) * columns)
    val more = n.items.size - shown.size
    Column(Modifier.padding(start = m.dp(8f), end = m.dp(8f), top = m.dp(6f))) {
        Row(verticalAlignment = Alignment.Bottom) {
            MetroText(label, MetroType.style(m.text(if (size == TileSize.MEDIUM) 15f else 18f)), color = ink, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            if (left > 0) MetroText("  $left left", MetroType.style(m.text(12f)), color = ink.copy(alpha = 0.85f), maxLines = 1)
        }
        Spacer(Modifier.height(m.dp(4f)))
        when {
            !hasText && n.items.isEmpty() -> MetroText("empty · tap to open", MetroType.style(m.text(13f)), color = ink.copy(alpha = 0.8f))
            !hasText && left == 0 -> MetroText("all ticked", MetroType.style(m.text(22f), FontWeight.Light), color = ink)
            else -> {
                if (hasText) MetroText(n.text, MetroType.style(m.text(13f)), color = ink, maxLines = textLines)
                if (hasText && shown.isNotEmpty()) Spacer(Modifier.height(m.dp(4f)))
                if (columns == 2 && shown.size > 1) {
                    val half = (shown.size + 1) / 2
                    Row(horizontalArrangement = Arrangement.spacedBy(m.dp(12f))) {
                        Column(Modifier.weight(1f)) { shown.take(half).forEach { NoteRow(it.text, it.done, m) } }
                        Column(Modifier.weight(1f)) { shown.drop(half).forEach { NoteRow(it.text, it.done, m) } }
                    }
                } else {
                    shown.forEach { NoteRow(it.text, it.done, m) }
                }
                if (more > 0) MetroText("+$more more", MetroType.style(m.text(12f)), color = ink.copy(alpha = 0.8f))
            }
        }
    }
}

/**
 * A handwriting tile: the whole drawing fitted to the tile, clear of its name. With "write on the
 * tile" on (wide and large tiles), the page itself at the tile's width instead, which a pen writes
 * on directly; every stroke is saved as the pen lifts. A finger still opens the tile.
 */
@Composable
private fun BoxScope.InkFace(id: Long, n: TileContent.Notes, size: TileSize, m: TileMetrics) {
    val ink = white()
    val stored = rememberInk(n.inkId)
    val writable = n.penOnTile && size.canWriteOnTile()
    if (writable) {
        val app = LocalContext.current.applicationContext as? io.github.danielphingston.metro.MetroApplication
        // The strokes being written here. The saved copy replaces them when it changes elsewhere (the
        // open page, a restore), but not while this tile's own saves are still on their way.
        val strokes = remember(n.inkId) { androidx.compose.runtime.mutableStateListOf<io.github.danielphingston.metro.data.InkStroke>() }
        var saving by remember(n.inkId) { mutableIntStateOf(0) }
        LaunchedEffect(stored) {
            if (saving == 0 && strokes.toList() != stored) {
                strokes.clear()
                strokes.addAll(stored)
            }
        }
        InkCanvas(
            strokes, n.penColor, ink, erasing = false, penOnly = true,
            onChange = {
                saving++
                app?.saveInk(n.inkId, strokes.toList()) { saving-- }
            },
        )
        return
    }
    if (stored.isEmpty()) {
        ToolGlyph(ToolGlyphKind.PEN, if (size == TileSize.SMALL) m.dp(36f) else m.dp(44f), Modifier.align(Alignment.Center))
    } else {
        val named = size != TileSize.SMALL && LocalTileDecor.current.showName
        InkPicture(
            stored, ink,
            Modifier.fillMaxSize().padding(start = m.dp(6f), end = m.dp(6f), top = m.dp(6f), bottom = if (named) m.dp(24f) else m.dp(6f)),
            fit = true,
        )
    }
}

/** Whether a tile of this size can be written on with a pen: wide and large only, the rest are too small to write on. */
internal fun TileSize.canWriteOnTile() = this == TileSize.WIDE || this == TileSize.LARGE

@Composable
private fun NoteRow(text: String, done: Boolean, m: TileMetrics) {
    val ink = white()
    Row(Modifier.padding(vertical = m.dp(2f)), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(m.dp(11f))) {
            val stroke = 1.5.dp.toPx()
            drawRect(ink, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            if (done) drawRect(ink)
        }
        Spacer(Modifier.width(m.dp(7f)))
        MetroText(text, MetroType.style(m.text(13f)), color = if (done) ink.copy(alpha = 0.6f) else ink, maxLines = 1)
    }
}

internal enum class ToolGlyphKind { LIST, TEXT, PEN, CALENDAR }

/** The hairline pictures of tool tiles that have nothing else to show. */
@Composable
internal fun ToolGlyph(kind: ToolGlyphKind, size: Dp, modifier: Modifier = Modifier, color: Color = white()) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val w = s * 0.055f
        when (kind) {
            ToolGlyphKind.LIST -> for (i in 0..2) {
                val y = s * (0.27f + i * 0.23f)
                drawRect(color, Offset(s * 0.12f, y - s * 0.06f), Size(s * 0.12f, s * 0.12f), style = Stroke(w))
                drawLine(color, Offset(s * 0.36f, y), Offset(s * 0.88f, y), w, StrokeCap.Round)
            }
            ToolGlyphKind.TEXT -> for (i in 0..3) {
                val y = s * (0.22f + i * 0.19f)
                drawLine(color, Offset(s * 0.12f, y), Offset(s * if (i == 3) 0.6f else 0.88f, y), w, StrokeCap.Round)
            }
            ToolGlyphKind.PEN -> {
                drawLine(color, Offset(s * 0.2f, s * 0.8f), Offset(s * 0.75f, s * 0.25f), w * 3f, StrokeCap.Round)
                drawLine(color, Offset(s * 0.12f, s * 0.9f), Offset(s * 0.2f, s * 0.8f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.1f, s * 0.92f), Offset(s * 0.9f, s * 0.92f), w * 0.8f, StrokeCap.Round)
            }
            ToolGlyphKind.CALENDAR -> {
                drawRect(color, Offset(s * 0.15f, s * 0.22f), Size(s * 0.7f, s * 0.64f), style = Stroke(w))
                drawLine(color, Offset(s * 0.15f, s * 0.38f), Offset(s * 0.85f, s * 0.38f), w)
                drawLine(color, Offset(s * 0.33f, s * 0.12f), Offset(s * 0.33f, s * 0.3f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.67f, s * 0.12f), Offset(s * 0.67f, s * 0.3f), w, StrokeCap.Round)
            }
        }
    }
}
