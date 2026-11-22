package io.github.danielphingston.metro.ui.tiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.ui.settings.SettingsActivity
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.InkStroke
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Drawing [id] from the ink store, read off the main thread and again whenever a drawing is
 * written (from the tile, the open page or a restored backup). Empty while it loads.
 */
@Composable
internal fun rememberInk(id: String): List<InkStroke> {
    if (id.isEmpty()) return emptyList()
    LocalInkSource.current?.let { return it(id) }
    val app = LocalContext.current.applicationContext as? MetroApplication ?: return emptyList()
    val changes by app.ink.changes.collectAsStateWithLifecycle()
    val strokes by produceState(emptyList(), id, changes) {
        value = withContext(Dispatchers.IO) { app.ink.read(id) }
    }
    return strokes
}

/** Whether Metro Plus is unlocked; true where there is no app to ask (previews, tests). */
@Composable
internal fun rememberMetroPlus(): Boolean {
    val app = LocalContext.current.applicationContext as? MetroApplication ?: return true
    val unlocked by app.purchases.quickTilesUnlocked.collectAsStateWithLifecycle()
    return unlocked
}

/** Drawings by id instead of the ink store, for previews and tests; null reads the store. */
internal val LocalInkSource = androidx.compose.runtime.staticCompositionLocalOf<((String) -> List<InkStroke>)?> { null }

/** The colour stroke [color] is drawn in, the plain ink being [plain]. */
internal fun inkColor(color: Int, plain: Color): Color = if (color == InkStroke.PLAIN) plain else Color(color)

/**
 * Handwriting drawn into the layer's cache, so scrolling start doesn't draw it again: to the
 * width of the space ([fit] false: the page as written, from its top), or the whole drawing
 * fitted and centred ([fit] true).
 */
@Composable
internal fun InkPicture(strokes: List<InkStroke>, plain: Color, modifier: Modifier = Modifier, fit: Boolean = false) {
    Box(
        modifier.clipToBounds().drawWithCache {
            onDrawBehind { if (fit) drawInkFitted(strokes, plain) else drawInk(strokes, plain) }
        },
    )
}

/**
 * Draws [strokes] scaled to fit this scope and centred, keeping their shape: a tile shows the
 * whole drawing whatever its size. A small drawing (a word) is enlarged at most about three times.
 */
internal fun DrawScope.drawInkFitted(strokes: List<InkStroke>, plain: Color) {
    val points = strokes.flatMap { it.points }
    if (points.isEmpty()) return
    val pad = 0.03f
    val minX = points.minOf { it.x } - pad
    val maxX = points.maxOf { it.x } + pad
    val minY = points.minOf { it.y } - pad
    val maxY = points.maxOf { it.y } + pad
    val scale = minOf(size.width / (maxX - minX), size.height / (maxY - minY), size.width / 0.35f)
    val ox = (size.width - (maxX - minX) * scale) / 2 - minX * scale
    val oy = (size.height - (maxY - minY) * scale) / 2 - minY * scale
    strokes.forEach { drawStroke(it.points, inkColor(it.color, plain), scale, ox, oy) }
}

/** Draws [strokes] scaled to this scope's width, from its top; line width follows pressure. */
internal fun DrawScope.drawInk(strokes: List<InkStroke>, plain: Color) {
    strokes.forEach { drawStroke(it.points, inkColor(it.color, plain), size.width, 0f, 0f) }
}

private fun DrawScope.drawStroke(points: List<InkPoint>, color: Color, scale: Float, ox: Float, oy: Float) {
    if (points.size == 1) {
        val p = points[0]
        drawCircle(color, scale * (0.0018f + 0.005f * p.pressure), Offset(ox + p.x * scale, oy + p.y * scale))
        return
    }
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        drawLine(color, Offset(ox + a.x * scale, oy + a.y * scale), Offset(ox + b.x * scale, oy + b.y * scale), scale * (0.0035f + 0.01f * b.pressure), StrokeCap.Round)
    }
}

private const val ERASER_RADIUS = 0.025f

/**
 * A page to write on, its width being the page's width. A pen draws with its pressure in [color],
 * and its button (or eraser end) erases; [erasing] makes every touch erase. With [penOnly] (a
 * tile on start) only a pen writes and every other touch passes through to the tile untouched
 * (a tap opens it, a hold enters edit mode); otherwise a finger writes too until a pen has
 * touched the page, after which fingers (a resting palm) are ignored. While a pen hovers, a dot
 * shows where it will land. Only the stroke being drawn is redrawn as the pen moves; the finished
 * strokes sit in their own layer. [onChange] follows each added or erased stroke.
 */
@Composable
internal fun InkCanvas(
    strokes: MutableList<InkStroke>,
    color: Int,
    plain: Color,
    erasing: Boolean,
    penOnly: Boolean,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = remember { mutableStateListOf<InkPoint>() }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var penSeen by remember { mutableStateOf(false) }
    val erasingNow by rememberUpdatedState(erasing)
    val colorNow by rememberUpdatedState(color)
    val changed by rememberUpdatedState(onChange)
    Box(
        modifier.fillMaxSize().clipToBounds()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        hover = when {
                            event.type == PointerEventType.Exit || change.pressed -> null
                            change.type == PointerType.Stylus -> change.position
                            else -> hover
                        }
                    }
                }
            }
            .pointerInput(penOnly) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (pen) {
                        penSeen = true
                    } else if (penOnly || (penSeen && down.type == PointerType.Touch)) {
                        return@awaitEachGesture
                    }
                    // The pen's side button (or its eraser end) erases, from the moment it is pressed: at
                    // touch-down or in the middle of a stroke, which then becomes erasing.
                    fun buttonHeld(buttons: androidx.compose.ui.input.pointer.PointerButtons) =
                        down.type == PointerType.Stylus && (buttons.isPrimaryPressed || buttons.isSecondaryPressed)
                    var erase = erasingNow || down.type == PointerType.Eraser || buttonHeld(currentEvent.buttons)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    fun point(position: Offset, pressure: Float) = InkPoint(position.x / w, position.y / w, if (pen) pressure.coerceIn(0f, 1f) else 0.45f)
                    fun eraseAt(position: Offset) {
                        val p = point(position, 0f)
                        if (strokes.removeAll { stroke -> stroke.points.any { hypot(it.x - p.x, it.y - p.y) < ERASER_RADIUS } }) changed()
                    }
                    down.consume()
                    if (erase) eraseAt(down.position) else {
                        active.clear()
                        active += point(down.position, down.pressure)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!erase && buttonHeld(event.buttons)) {
                            erase = true
                            active.clear()
                        }
                        if (erase) {
                            eraseAt(change.position)
                        } else {
                            change.historical.forEach { active += point(it.position, change.pressure) }
                            active += point(change.position, change.pressure)
                        }
                        change.consume()
                    }
                    if (!erase && active.isNotEmpty()) {
                        strokes += InkStroke(InkCodec.simplify(active.toList()), colorNow)
                        active.clear()
                        changed()
                    }
                }
            },
    ) {
        // Finished strokes: their own layer, redrawn only when a stroke is added or erased.
        Box(Modifier.fillMaxSize().graphicsLayer().drawBehind { drawInk(strokes.toList(), plain) })
        // The stroke being drawn and the hover dot.
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (active.isNotEmpty()) drawInk(listOf(InkStroke(active.toList(), colorNow)), plain)
                hover?.let { drawCircle(inkColor(colorNow, plain).copy(alpha = 0.6f), 3.dp.toPx(), it) }
            },
        )
    }
}

/** The quick pen colours: the plain ink first, then a spread most people reach for. */
internal val PenColors = listOf(
    InkStroke.PLAIN,
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFFEC407A.toInt(), 0xFF000000.toInt(),
)

/**
 * The pen colour choice: round swatches (the plain ink drawn as a ring in [plain]) and "more…",
 * which opens a full colour picker. Colours are Metro Plus: while it is locked only the plain ink
 * can be picked, and the colours open the Metro Plus page instead.
 */
@Composable
internal fun PenColorRow(color: Int, plain: Color, onPick: (Int) -> Unit) {
    val context = LocalContext.current
    val unlocked = rememberMetroPlus()
    val onLocked = { context.startActivity(SettingsActivity.intent(context, SettingsPage.METRO_PLUS)) }
    var picking by remember { mutableStateOf(false) }
    val swatches = if (color in PenColors) PenColors else PenColors + color
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        swatches.forEach { c ->
            val selected = c == color
            val label = if (c == InkStroke.PLAIN) "plain ink" else "colour #%06X".format(c and 0xFFFFFF)
            Box(
                Modifier
                    .size(36.dp)
                    .border(if (selected) 3.dp else 0.dp, if (selected) Metro.colors.accent else Color.Transparent, CircleShape)
                    .padding(5.dp)
                    .then(if (c == InkStroke.PLAIN) Modifier.border(2.dp, plain, CircleShape) else Modifier.background(Color(c), CircleShape))
                    .then(if (c != InkStroke.PLAIN && Color(c).luminanceApprox() < 0.08f) Modifier.border(1.dp, Metro.colors.subtle, CircleShape) else Modifier)
                    .clickable(onClickLabel = "pen $label") { if (c == InkStroke.PLAIN || unlocked) onPick(c) else onLocked() }
                    .semantics {
                        contentDescription = label
                        this.selected = selected
                    },
            )
        }
        Box(
            Modifier.size(36.dp).clickable(onClickLabel = "more colours") { if (unlocked) picking = true else onLocked() },
            contentAlignment = Alignment.Center,
        ) { MetroText("…", MetroType.sectionTitle, color = Metro.colors.foreground) }
    }
    if (!unlocked) MetroText("colours are part of Metro Plus", MetroType.caption, color = Metro.colors.subtle, modifier = Modifier.padding(top = 6.dp))
    if (picking) {
        ColorPicker(initial = if (color == InkStroke.PLAIN) 0xFF1E88E5.toInt() else color, onPick = { picking = false; onPick(it) }, onDismiss = { picking = false })
    }
}

private fun Color.luminanceApprox() = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/**
 * Any colour: a square of saturation (across) and brightness (down) for the hue chosen on the
 * strip below it, with the result shown beside "use".
 */
@Composable
internal fun ColorPicker(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var sat by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    val picked = Color.hsv(hue, sat, value)
    MetroFlyout(onDismiss, paneTitle = "pen colour") {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .clickable(enabled = false, onClick = {})
                .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            MetroText("pen colour", MetroType.sectionTitle, modifier = Modifier.padding(bottom = 12.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .semantics { contentDescription = "saturation and brightness" }
                    .pointerInput(Unit) {
                        fun set(p: Offset) {
                            sat = (p.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        }
                        detectTapGestures { set(it) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            sat = (change.position.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        }
                    },
            ) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val at = Offset(sat * size.width, (1f - value) * size.height)
                drawCircle(Color.White, 9.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                drawCircle(Color.Black, 11.dp.toPx(), at, style = Stroke(1.dp.toPx()))
            }
            Spacer(Modifier.height(16.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .semantics { contentDescription = "hue" }
                    .pointerInput(Unit) { detectTapGestures { hue = (it.x / size.width).coerceIn(0f, 1f) * 359.9f } }
                    .pointerInput(Unit) { detectDragGestures { change, _ -> hue = (change.position.x / size.width).coerceIn(0f, 1f) * 359.9f } },
            ) {
                drawRect(Brush.horizontalGradient(List(7) { Color.hsv(it * 60f % 360f, 1f, 1f) }))
                val x = hue / 360f * size.width
                drawRect(Color.White, Offset(x - 2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(4.dp.toPx(), size.height))
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(44.dp).background(picked, CircleShape).border(1.dp, Metro.colors.subtle, CircleShape))
                Spacer(Modifier.weight(1f))
                MetroButton("cancel", onDismiss)
                MetroButton("use", { onPick(picked.toArgb()) })
            }
        }
    }
}
