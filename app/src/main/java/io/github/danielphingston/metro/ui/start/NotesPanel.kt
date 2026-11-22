package io.github.danielphingston.metro.ui.start

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
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
import io.github.danielphingston.metro.data.InkStroke
import io.github.danielphingston.metro.data.NoteItem
import io.github.danielphingston.metro.data.NoteStyle
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.ToolLimits
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.tiles.InkCanvas
import io.github.danielphingston.metro.ui.tiles.InkHistory
import io.github.danielphingston.metro.ui.tiles.PenColorRow
import io.github.danielphingston.metro.ui.tiles.PenColors
import io.github.danielphingston.metro.ui.tiles.rememberInk
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlin.math.ceil
import kotlinx.coroutines.flow.first

/**
 * The open notes tile: its content in a band under the tile, like an open folder. Ticking a list
 * item is the only change it makes as it is; everything else (adding, typing) waits for "edit", so
 * a stray tap can't change a note. While editing, [draft] holds the changes; they are saved on
 * "done", when the band closes, and when the launcher stops. An ink tile opens as [InkPanel].
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
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = draft != null
    val shown = draft ?: notes
    val fg = Metro.colors.foreground
    Column(modifier.padding(horizontal = PanelPadding, vertical = PanelVertical)) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                PlainField(
                    shown.name, { onDraft(shown.copy(name = it.take(ToolLimits.NAME))) },
                    MetroType.sectionTitle, placeholder = label, modifier = Modifier.weight(1f),
                )
            } else {
                MetroText(label, MetroType.sectionTitle, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.width(8.dp))
            // Plain icons, no boxes: edit; while editing, cancel (puts the note back as it was) and done.
            if (editing) {
                HeaderIcon("cancel", onCancel) { color, stroke -> drawCross(color, stroke) }
                HeaderIcon("done", onDone) { color, stroke -> drawTick(color, stroke) }
            } else {
                HeaderIcon("edit", onEdit) { color, stroke -> drawPencil(color, stroke) }
            }
        }
        if (editing) NoteEdit(shown, onDraft) else NoteView(shown, onTick)
    }
}

/** An open note as it reads: its text, then its checklist, in one area that scrolls when long. Items tick with a tap. */
@Composable
private fun NoteView(notes: TileContent.Notes, onTick: (Int) -> Unit) {
    val fg = Metro.colors.foreground
    Column(Modifier.fillMaxWidth().heightIn(max = ViewMax).verticalScroll(rememberScrollState())) {
        if (notes.text.isBlank() && notes.items.isEmpty()) {
            MetroText("nothing here yet", MetroType.body, color = Metro.colors.subtle, modifier = Modifier.height(ItemHeight).padding(top = 8.dp))
        }
        if (notes.text.isNotBlank()) MetroText(notes.text, MetroType.body, modifier = Modifier.padding(vertical = 8.dp))
        notes.items.forEachIndexed { i, item ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = ItemHeight)
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
                    color = if (item.done) fg.copy(alpha = 0.5f) else fg, maxLines = 2,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * An open note being edited: a text field of a fixed height (it scrolls inside), then the
 * checklist, which scrolls in a band of a few rows with the "add an item" field under it.
 */
@Composable
private fun NoteEdit(notes: TileContent.Notes, onDraft: (TileContent.Notes) -> Unit) {
    PlainField(
        notes.text, { onDraft(notes.copy(text = it.take(ToolLimits.TEXT))) }, MetroType.body, placeholder = "a few lines, if you like",
        singleLine = false, modifier = Modifier.fillMaxWidth().height(NoteTextHeight).padding(vertical = 8.dp),
    )
    LimitNote(notes.text.length, ToolLimits.TEXT, "characters")
    var adding by remember { mutableStateOf("") }
    val addFocus = remember { FocusRequester() }
    val full = notes.items.size >= ToolLimits.ITEMS
    fun add() {
        val text = adding.trim()
        if (text.isEmpty() || full) return
        onDraft(notes.copy(items = notes.items + NoteItem(text)))
        adding = ""
    }
    // The items scroll inside a band of at most a few rows; the "add" field stays under them.
    val scroll = rememberScrollState()
    var count by remember { mutableIntStateOf(notes.items.size) }
    LaunchedEffect(notes.items.size) {
        // A new item shows at the bottom, next to the field it came from.
        if (notes.items.size > count) {
            snapshotFlow { scroll.maxValue }.first { it > scroll.value || it == 0 }
            scroll.animateScrollTo(scroll.maxValue)
        }
        count = notes.items.size
    }
    Column(Modifier.fillMaxWidth().heightIn(max = EditListMax).verticalScroll(scroll)) {
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
                    { text -> onDraft(notes.copy(items = notes.items.mapIndexed { j, it -> if (j == i) it.copy(text = text.take(ToolLimits.ITEM)) else it })) },
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
    }
    if (full) {
        MetroText("the list is full (${ToolLimits.ITEMS} items)", MetroType.caption, color = Metro.colors.subtle, modifier = Modifier.height(EditItemHeight).padding(top = 14.dp))
    } else {
        Row(Modifier.fillMaxWidth().height(EditItemHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { MetroText("+", MetroType.sectionTitle, color = Metro.colors.subtle) }
            PlainField(
                adding, { adding = it.take(ToolLimits.ITEM) }, MetroType.body, placeholder = "add an item",
                imeAction = ImeAction.Next, onIme = ::add,
                modifier = Modifier.weight(1f).focusRequester(addFocus),
            )
        }
    }
    // A new note starts with the keyboard up, ready for its first item.
    LaunchedEffect(Unit) { if (notes.items.isEmpty() && notes.text.isEmpty()) runCatching { addFocus.requestFocus() } }
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

/**
 * The open ink tile: a page that is always ready to write on, with the pen, eraser, undo, clear and
 * the pen's colour above it. Only the name waits for the pencil, so a stray tap can't open the
 * keyboard. The drawing is written when the band closes and when the launcher stops (only if it
 * changed), not per stroke; undo goes back stroke by stroke to how the page was when it opened.
 * A colour or a name is saved as soon as it is picked or finished, through [onUpdate].
 */
@Composable
internal fun InkPanel(
    notes: TileContent.Notes,
    label: String,
    onUpdate: ((TileContent.Notes) -> TileContent.Notes) -> Unit,
    onSaveInk: (List<InkStroke>) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the name is being typed, so the start screen can make room for the keyboard. */
    onNaming: (Boolean) -> Unit = {},
) {
    val stored = rememberInk(notes.inkId)
    val strokes = remember { mutableStateListOf<InkStroke>() }
    val history = remember { InkHistory() }
    // Written on since the band opened, and changed since the drawing was last saved.
    var touched by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // Whether the file's drawing has reached the page. It is read off the main thread, so in a rare
    // race writing starts first: the drawing then goes under the new strokes instead of being lost.
    var seenFile by remember { mutableStateOf(false) }
    LaunchedEffect(stored) {
        if (seenFile || stored.isEmpty()) return@LaunchedEffect
        seenFile = true
        if (touched) strokes.addAll(0, stored) else strokes.addAll(stored)
    }
    var penColor by remember(notes.penColor) { mutableStateOf(notes.penColor) }
    var name by remember { mutableStateOf(notes.name) }
    var naming by remember { mutableStateOf(false) }
    val latest by androidx.compose.runtime.rememberUpdatedState(notes)
    val update by androidx.compose.runtime.rememberUpdatedState(onUpdate)
    val saveInk by androidx.compose.runtime.rememberUpdatedState(onSaveInk)
    val namingChanged by androidx.compose.runtime.rememberUpdatedState(onNaming)
    fun saveName() {
        val clean = name.take(ToolLimits.NAME)
        if (clean != latest.name) update { it.copy(name = clean) }
    }
    fun save() {
        if (dirty) {
            saveInk(strokes.toList())
            dirty = false
            seenFile = true
        }
        if (naming) saveName()
    }
    fun finishNaming() {
        saveName()
        naming = false
        namingChanged(false)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            save()
            if (naming) namingChanged(false)
        }
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) { save() }
    fun changed() {
        touched = true
        dirty = true
    }
    val plain = Metro.colors.foreground
    Column(modifier.padding(horizontal = PanelPadding, vertical = PanelVertical)) {
        Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
            if (naming) {
                val focus = remember { FocusRequester() }
                PlainField(
                    name, { name = it.take(ToolLimits.NAME) }, MetroType.sectionTitle, placeholder = NoteStyle.INK.label,
                    onIme = ::finishNaming, modifier = Modifier.weight(1f).focusRequester(focus),
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Spacer(Modifier.width(8.dp))
                HeaderIcon("done", ::finishNaming) { color, stroke -> drawTick(color, stroke) }
            } else {
                val shown = if (name == notes.name) label else name.ifBlank { NoteStyle.INK.label }
                MetroText(shown, MetroType.sectionTitle, maxLines = 1, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                HeaderIcon("rename", {
                    naming = true
                    namingChanged(true)
                }) { color, stroke -> drawPencil(color, stroke) }
            }
        }
        var tool by remember { mutableStateOf(InkTool.PEN) }
        Row(Modifier.fillMaxWidth().height(InkToolsHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MetroChoice("pen", tool == InkTool.PEN, { tool = InkTool.PEN }, Modifier.weight(1f))
            MetroChoice("eraser", tool == InkTool.ERASER, { tool = InkTool.ERASER }, Modifier.weight(1f))
            // Clearing asks once more (a second tap within a few seconds), and undo brings it back.
            var confirming by remember { mutableStateOf(false) }
            LaunchedEffect(confirming) {
                if (confirming) {
                    kotlinx.coroutines.delay(3_000)
                    confirming = false
                }
            }
            MetroChoice(if (confirming) "clear all?" else "clear", confirming, {
                if (confirming && strokes.isNotEmpty()) {
                    history.record(strokes)
                    strokes.clear()
                    changed()
                }
                confirming = !confirming
            }, Modifier.weight(1f))
            HeaderIcon("undo", {
                history.undo()?.let {
                    strokes.clear()
                    strokes.addAll(it)
                    changed()
                }
            }, enabled = history.size > 0) { color, stroke -> drawUndo(color, stroke) }
        }
        Box(Modifier.padding(vertical = 6.dp)) {
            PenColorRow(penColor, plain) { picked ->
                tool = InkTool.PEN
                penColor = picked
                update { it.copy(penColor = picked) }
            }
        }
        // Square on a phone; on a wide screen no taller than InkMax, so the band stays in view.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Box(
                Modifier.fillMaxWidth().height(minOf(maxWidth, InkMax)).padding(vertical = 4.dp)
                    .background(Metro.colors.background.copy(alpha = 0.5f))
                    .border(1.dp, Metro.colors.subtle.copy(alpha = 0.4f)),
            ) {
                InkCanvas(
                    strokes, penColor, plain, erasing = tool == InkTool.ERASER, penOnly = false,
                    onStart = { history.begin(strokes) },
                    onChange = {
                        history.changed()
                        changed()
                    },
                    modifier = Modifier.semantics { contentDescription = "ink page" },
                )
            }
        }
    }
}

/** A plain icon in the band's header: no box, a full-size touch target, its [label] for TalkBack. */
@Composable
private fun HeaderIcon(label: String, onClick: () -> Unit, enabled: Boolean = true, glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color, Float) -> Unit) {
    val color = Metro.colors.foreground.let { if (enabled) it else it.copy(alpha = 0.3f) }
    Box(
        Modifier
            .size(48.dp)
            .then(if (enabled) Modifier.metroTilt() else Modifier)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) { glyph(color, 2.dp.toPx()) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCross(color: Color, stroke: Float) {
    val i = size.minDimension * 0.22f
    drawLine(color, Offset(i, i), Offset(size.width - i, size.height - i), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, Offset(size.width - i, i), Offset(i, size.height - i), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTick(color: Color, stroke: Float) {
    val s = size.minDimension
    drawLine(color, Offset(s * 0.14f, s * 0.52f), Offset(s * 0.4f, s * 0.78f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    drawLine(color, Offset(s * 0.4f, s * 0.78f), Offset(s * 0.88f, s * 0.24f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
}

/** A curved arrow back, for undo. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUndo(color: Color, stroke: Float) {
    val s = size.minDimension
    val cap = androidx.compose.ui.graphics.StrokeCap.Round
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(s * 0.2f, s * 0.4f)
        lineTo(s * 0.6f, s * 0.4f)
        cubicTo(s * 0.92f, s * 0.4f, s * 0.92f, s * 0.82f, s * 0.6f, s * 0.82f)
        lineTo(s * 0.36f, s * 0.82f)
    }
    drawPath(path, color, style = Stroke(stroke, cap = cap, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(color, Offset(s * 0.2f, s * 0.4f), Offset(s * 0.36f, s * 0.24f), stroke, cap)
    drawLine(color, Offset(s * 0.2f, s * 0.4f), Offset(s * 0.36f, s * 0.56f), stroke, cap)
}

/** "1,950 of 2,000 characters" once a field is nearly full, so it doesn't stop typing unexplained. */
@Composable
private fun LimitNote(used: Int, max: Int, what: String) {
    if (used < max * 9 / 10) return
    MetroText("$used of $max $what", MetroType.caption, color = if (used >= max) Metro.colors.accent else Metro.colors.subtle)
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

private val PanelPadding = 12.dp
private val PanelVertical = 8.dp
private val HeaderHeight = 52.dp
private val ItemHeight = 44.dp
private val EditItemHeight = 48.dp
private val ClearHeight = 52.dp
private val InkToolsHeight = 48.dp
private val NoteTextHeight = 128.dp
private val LimitHeight = 20.dp
private val ViewMax = 400.dp
private val EditListMax = 240.dp
private val InkMax = 420.dp
private val Swatch = 36.dp
private val SwatchGap = 10.dp

/**
 * How tall the open band is, in grid rows, so the tiles below can move out of its way. Every part
 * has a ceiling (a long list or note scrolls inside), so the band is never more than about a
 * screen. Used for the first frame; after that the band's measured height counts.
 */
internal object NotesPanelSize {
    fun rows(notes: TileContent.Notes, editing: Boolean, cell: Dp, width: Dp, measurer: TextMeasurer, density: Density): Int {
        val inner = width - PanelPadding * 2
        val body: Dp = when (notes.style) {
            NoteStyle.NOTE -> if (editing) {
                NoteTextHeight + LimitHeight + minOf(EditItemHeight * notes.items.size, EditListMax) + EditItemHeight +
                    if (notes.items.any { it.done }) ClearHeight else 0.dp
            } else {
                val text = if (notes.text.isBlank()) 0.dp else with(density) {
                    measurer.measure(notes.text, MetroType.body, constraints = Constraints(maxWidth = inner.roundToPx().coerceAtLeast(1))).size.height.toDp()
                } + 16.dp
                val items = ItemHeight * if (notes.items.isEmpty() && notes.text.isBlank()) 1 else notes.items.size
                minOf(text + items, ViewMax)
            }
            // The tools, the colours (wrapping onto as many lines as the width needs) and the page.
            NoteStyle.INK -> {
                val swatches = PenColors.size + (if (notes.penColor in PenColors) 0 else 1) + 1
                val perLine = ((inner + SwatchGap) / (Swatch + SwatchGap)).toInt().coerceAtLeast(1)
                val lines = (swatches + perLine - 1) / perLine
                InkToolsHeight + Swatch * lines + SwatchGap * (lines - 1) + 12.dp + minOf(inner, InkMax) + 8.dp
            }
        }
        val total = PanelVertical * 2 + HeaderHeight + body
        return ceil(total.value / cell.value).toInt().coerceAtLeast(2)
    }
}
