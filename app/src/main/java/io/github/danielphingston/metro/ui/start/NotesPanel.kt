package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.InkCodec
import io.github.danielphingston.metro.data.InkPoint
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.tiles.InkPicture
import io.github.danielphingston.metro.ui.tiles.drawInk
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The open notes tile: its content in a band under the tile, like an open folder. Ticking a list
 * item is the only change it makes as it is; everything else (adding, typing, writing, switching
 * style) waits for "edit", so a stray tap can't change a note. While editing, [draft] holds the
 * changes; they are saved on "done", when the band closes, and when the launcher stops.
 */
@Composable
internal fun NotesPanel(
    notes: TileContent.Notes,
    label: String,
    draft: TileContent.Notes?,
    onTick: (index: Int) -> Unit,
    onEdit: () -> Unit,
    onDraft: (TileContent.Notes) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = draft != null
    val shown = draft ?: notes
    val fg = Metro.colors.foreground
    Column(modifier.padding(horizontal = PanelPadding, vertical = PanelVertical)) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                PlainField(
                    shown.name, { onDraft(shown.copy(name = it)) },
                    MetroType.sectionTitle, placeholder = label, modifier = Modifier.weight(1f),
                )
            } else {
                MetroText(label, MetroType.sectionTitle, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(12.dp))
            MetroButton(if (editing) "done" else "edit", if (editing) onDone else onEdit)
        }
        if (editing) {
            Row(Modifier.fillMaxWidth().height(StylesHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NoteStyle.entries.forEach { style ->
                    MetroChoice(style.id, shown.style == style, { onDraft(NotesPanelSize.switchStyle(shown, style)) }, Modifier.weight(1f))
                }
            }
        }
        when (shown.style) {
            NoteStyle.LIST -> ListBody(shown, editing, onTick, onDraft)
            NoteStyle.TEXT -> if (editing) {
                PlainField(
                    shown.text, { onDraft(shown.copy(text = it)) }, MetroType.body, placeholder = "write something",
                    singleLine = false, modifier = Modifier.fillMaxWidth().heightIn(min = TextEditMin - 16.dp).padding(vertical = 8.dp),
                )
            } else {
                MetroText(shown.text.ifBlank { "empty note" }, MetroType.body, color = if (shown.text.isBlank()) Metro.colors.subtle else fg, modifier = Modifier.padding(vertical = 8.dp))
            }
            NoteStyle.INK -> InkBody(shown, editing, onDraft)
        }
    }
}

@Composable
private fun ListBody(notes: TileContent.Notes, editing: Boolean, onTick: (Int) -> Unit, onDraft: (TileContent.Notes) -> Unit) {
    val fg = Metro.colors.foreground
    if (!editing) {
        if (notes.items.isEmpty()) {
            MetroText("nothing on the list yet", MetroType.body, color = Metro.colors.subtle, modifier = Modifier.height(ItemHeight).padding(top = 8.dp))
        }
        notes.items.forEachIndexed { i, item ->
            Row(
                Modifier.fillMaxWidth().height(ItemHeight)
                    .clickable(onClickLabel = if (item.done) "untick" else "tick") { onTick(i) }
                    .semantics(mergeDescendants = true) {
                        role = Role.Checkbox
                        stateDescription = if (item.done) "done" else "not done"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckBox(item.done)
                Spacer(Modifier.width(14.dp))
                MetroText(
                    item.text, MetroType.body.copy(textDecoration = if (item.done) TextDecoration.LineThrough else null),
                    color = if (item.done) fg.copy(alpha = 0.5f) else fg, maxLines = 1,
                )
            }
        }
        return
    }
    var adding by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    fun add() {
        val text = adding.trim()
        if (text.isEmpty()) return
        onDraft(notes.copy(items = notes.items + NoteItem(text)))
        adding = ""
    }
    notes.items.forEachIndexed { i, item ->
        Row(Modifier.fillMaxWidth().height(EditItemHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clickable(onClickLabel = if (item.done) "untick" else "tick") {
                    onDraft(notes.copy(items = notes.items.mapIndexed { j, it -> if (j == i) it.copy(done = !it.done) else it }))
                },
                contentAlignment = Alignment.Center,
            ) { CheckBox(item.done) }
            PlainField(
                item.text,
                { text -> onDraft(notes.copy(items = notes.items.mapIndexed { j, it -> if (j == i) it.copy(text = text) else it })) },
                MetroType.body, modifier = Modifier.weight(1f),
            )
            Box(
                Modifier.size(40.dp).clickable(onClickLabel = "remove ${item.text}") {
                    onDraft(notes.copy(items = notes.items.filterIndexed { j, _ -> j != i }))
                }.semantics { contentDescription = "remove ${item.text}" },
                contentAlignment = Alignment.Center,
            ) { MetroText("✕", MetroType.body, color = Metro.colors.subtle) }
        }
    }
    Row(Modifier.fillMaxWidth().height(EditItemHeight), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { MetroText("+", MetroType.sectionTitle, color = Metro.colors.subtle) }
        PlainField(
            adding, { adding = it }, MetroType.body, placeholder = "add an item",
            imeAction = ImeAction.Next, onIme = ::add,
            modifier = Modifier.weight(1f).focusRequester(addFocus),
        )
    }
    // A new list starts with the keyboard up, ready for its first item.
    LaunchedEffect(Unit) { if (notes.items.isEmpty()) runCatching { addFocus.requestFocus() } }
    if (notes.items.any { it.done }) {
        Box(Modifier.height(ClearHeight), contentAlignment = Alignment.CenterStart) {
            MetroButton("clear ticked items", { onDraft(notes.copy(items = notes.items.filterNot { it.done })) })
        }
    }
}

@Composable
private fun CheckBox(done: Boolean) {
    val fg = Metro.colors.foreground
    val accent = Metro.colors.accent
    Canvas(Modifier.size(20.dp)) {
        val stroke = 2.dp.toPx()
        if (done) {
            drawRect(accent)
            val s = size.minDimension
            drawLine(Color.White, Offset(s * 0.22f, s * 0.52f), Offset(s * 0.42f, s * 0.72f), stroke)
            drawLine(Color.White, Offset(s * 0.42f, s * 0.72f), Offset(s * 0.8f, s * 0.3f), stroke)
        } else {
            drawRect(fg, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        }
    }
}

private enum class InkTool { PEN, ERASER }

@Composable
private fun InkBody(notes: TileContent.Notes, editing: Boolean, onDraft: (TileContent.Notes) -> Unit) {
    // Decoded once per edit; while editing, the strokes here are the truth and the draft follows them.
    val strokes = remember(editing) { mutableStateListOf<List<InkPoint>>().apply { addAll(InkCodec.decode(notes.ink)) } }
    var tool by remember { mutableStateOf(InkTool.PEN) }
    if (editing) {
        Row(Modifier.fillMaxWidth().height(InkToolsHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MetroChoice("pen", tool == InkTool.PEN, { tool = InkTool.PEN }, Modifier.weight(1f))
            MetroChoice("eraser", tool == InkTool.ERASER, { tool = InkTool.ERASER }, Modifier.weight(1f))
            MetroChoice("clear", false, {
                strokes.clear()
                onDraft(notes.copy(ink = ""))
            }, Modifier.weight(1f))
        }
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).padding(vertical = 4.dp)
            .background(Metro.colors.background.copy(alpha = 0.5f))
            .border(1.dp, Metro.colors.subtle.copy(alpha = 0.4f)),
    ) {
        if (editing) {
            InkCanvas(strokes, tool == InkTool.ERASER) { onDraft(notes.copy(ink = InkCodec.encode(strokes))) }
        } else if (strokes.isEmpty()) {
            MetroText("tap edit to write", MetroType.body, color = Metro.colors.subtle, modifier = Modifier.align(Alignment.Center))
        } else {
            InkPicture(strokes, Metro.colors.foreground, Modifier.fillMaxSize())
        }
    }
}

/**
 * A page to write on. A pen draws with its pressure, and its button (or its eraser end) erases;
 * a finger draws too until a pen has touched the page, after which fingers (a resting palm) are
 * ignored. While a pen hovers, a dot shows where it will land. Only the stroke being drawn is
 * redrawn as the pen moves; the finished strokes sit in their own layer.
 */
@Composable
private fun InkCanvas(strokes: MutableList<List<InkPoint>>, erasing: Boolean, onChange: () -> Unit) {
    val ink = Metro.colors.foreground
    val active = remember { mutableStateListOf<InkPoint>() }
    var hover by remember { mutableStateOf<Offset?>(null) }
    var penSeen by remember { mutableStateOf(false) }
    val erasingNow by androidx.compose.runtime.rememberUpdatedState(erasing)
    Box(
        Modifier.fillMaxSize().clipToBounds()
            .semantics { contentDescription = "handwriting page" }
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
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pen = down.type == PointerType.Stylus || down.type == PointerType.Eraser
                    if (pen) penSeen = true else if (penSeen && down.type == PointerType.Touch) return@awaitEachGesture
                    val buttons = currentEvent.buttons
                    val erase = erasingNow || down.type == PointerType.Eraser ||
                        (down.type == PointerType.Stylus && (buttons.isPrimaryPressed || buttons.isSecondaryPressed))
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    fun point(position: Offset, pressure: Float) = InkPoint(position.x / w, position.y / w, if (pen) pressure.coerceIn(0f, 1f) else 0.45f)
                    fun eraseAt(position: Offset) {
                        val p = point(position, 0f)
                        val before = strokes.size
                        strokes.removeAll { stroke -> stroke.any { hypot(it.x - p.x, it.y - p.y) < ERASER_RADIUS } }
                        if (strokes.size != before) onChange()
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
                        if (erase) {
                            eraseAt(change.position)
                        } else {
                            change.historical.forEach { active += point(it.position, change.pressure) }
                            active += point(change.position, change.pressure)
                        }
                        change.consume()
                    }
                    if (!erase && active.isNotEmpty()) {
                        strokes += InkCodec.simplify(active.toList())
                        active.clear()
                        onChange()
                    }
                }
            },
    ) {
        // Finished strokes: their own layer, redrawn only when a stroke is added or erased.
        Box(Modifier.fillMaxSize().graphicsLayer().drawBehind { drawInk(strokes.toList(), ink) })
        // The stroke being drawn and the hover dot.
        Box(
            Modifier.fillMaxSize().drawBehind {
                if (active.isNotEmpty()) drawInk(listOf(active.toList()), ink)
                hover?.let { drawCircle(ink.copy(alpha = 0.5f), 3.dp.toPx(), it) }
            },
        )
    }
}

/** A text field drawn like Metro text, with an underline while it can be typed in. */
@Composable
private fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    imeAction: ImeAction = ImeAction.Done,
    onIme: () -> Unit = {},
) {
    val fg = Metro.colors.foreground
    BasicTextField(
        value,
        onChange,
        modifier = modifier,
        singleLine = singleLine,
        textStyle = style.copy(color = fg),
        cursorBrush = SolidColor(Metro.colors.accent),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = if (singleLine) imeAction else ImeAction.Default),
        keyboardActions = KeyboardActions(onNext = { onIme() }, onDone = { onIme() }),
        decorationBox = { inner ->
            Box(Modifier.drawBehind { drawLine(fg.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }.padding(vertical = 6.dp)) {
                if (value.isEmpty()) MetroText(placeholder, style, color = Metro.colors.subtle, maxLines = 1)
                inner()
            }
        },
    )
}

private const val ERASER_RADIUS = 0.025f
private val PanelPadding = 12.dp
private val PanelVertical = 8.dp
private val HeaderHeight = 52.dp
private val StylesHeight = 48.dp
private val ItemHeight = 44.dp
private val EditItemHeight = 48.dp
private val ClearHeight = 52.dp
private val InkToolsHeight = 48.dp
private val TextEditMin = 200.dp

/** How tall the open band is, in grid rows, so the tiles below can move out of its way. */
internal object NotesPanelSize {
    fun rows(notes: TileContent.Notes, editing: Boolean, cell: Dp, width: Dp, measurer: TextMeasurer, density: Density): Int {
        val inner = width - PanelPadding * 2
        val body: Dp = when (notes.style) {
            NoteStyle.LIST -> if (editing) {
                EditItemHeight * (notes.items.size + 1) + if (notes.items.any { it.done }) ClearHeight else 0.dp
            } else {
                ItemHeight * notes.items.size.coerceAtLeast(1)
            }
            NoteStyle.TEXT -> {
                val measured = with(density) {
                    measurer.measure(notes.text.ifBlank { " " }, MetroType.body, constraints = Constraints(maxWidth = inner.roundToPx().coerceAtLeast(1))).size.height.toDp()
                } + 16.dp
                if (editing) maxOf(measured + 24.dp, TextEditMin) else measured
            }
            NoteStyle.INK -> inner + 8.dp + if (editing) InkToolsHeight else 0.dp
        }
        val total = PanelVertical * 2 + HeaderHeight + (if (editing) StylesHeight else 0.dp) + body
        return ceil(total.value / cell.value).toInt().coerceAtLeast(2)
    }

    /** [notes] shown as [style]; a list and a note take each other's words when the other is empty. */
    fun switchStyle(notes: TileContent.Notes, style: NoteStyle): TileContent.Notes = when {
        style == notes.style -> notes
        style == NoteStyle.TEXT && notes.text.isBlank() && notes.items.isNotEmpty() ->
            notes.copy(style = style, text = notes.items.joinToString("\n") { it.text })
        style == NoteStyle.LIST && notes.items.isEmpty() && notes.text.isNotBlank() ->
            notes.copy(style = style, items = notes.text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { NoteItem(it) })
        else -> notes.copy(style = style)
    }
}
