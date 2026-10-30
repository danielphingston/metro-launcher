package io.github.danielphingston.metro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.ChipEdit
import io.github.danielphingston.metro.data.SearchKeywords
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** How a [SearchField] looks. */
enum class SearchFieldStyle {
    /** A slim tinted box with a magnifier; the border turns accent while focused. */
    SLIM,

    /** A bordered box that turns accent once something is typed, without a magnifier. */
    OUTLINED,
}

/**
 * The one text box all of Metro's searches share (app list, search panel, widget picker, city
 * lookup). [placeholder] shows while empty and is also the field's accessibility label, so
 * screen readers announce what the empty field is for. [modifier] is applied before
 * [padding], so callers can attach turnstile or focus modifiers outside the padding.
 *
 * [chip] (slim style only) is drawn before the text, as a search keyword's target is. [query] is
 * then only the text after the chip. The field keeps an invisible guard character in front of
 * it, so a backspace at the start of the text, from a keyboard or the IME, is seen as a change
 * and reported through [onChipBackspace] instead of doing nothing. When the text is replaced from
 * outside (the chip turning back into letters), the cursor goes to [cursorAfterReset], else to the end.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: SearchFieldStyle = SearchFieldStyle.SLIM,
    padding: PaddingValues = PaddingValues(0.dp),
    onSearch: (() -> Unit)? = null,
    chip: (@Composable () -> Unit)? = null,
    onChipBackspace: () -> Unit = {},
    cursorAfterReset: Int? = null,
) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    val slim = style == SearchFieldStyle.SLIM
    val textStyle: TextStyle = if (slim) MetroType.listItem else MetroType.body
    val guarded = chip != null && slim
    // Select-all copy or cut would otherwise put the invisible guard on the clipboard.
    val clipboard = LocalClipboard.current
    val fieldClipboard = remember(clipboard, guarded) { if (guarded) GuardFreeClipboard(clipboard) else clipboard }
    CompositionLocalProvider(LocalClipboard provides fieldClipboard) {
    val shown = if (guarded) SearchKeywords.GUARD + query else query
    var field by remember { mutableStateOf(TextFieldValue(shown, TextRange(shown.length))) }
    // Text that changed from outside (not typed here) puts the cursor where the caller asks, else at the end.
    if (field.text != shown) field = TextFieldValue(shown, TextRange((cursorAfterReset ?: shown.length).coerceIn(0, shown.length)))
    BasicTextField(
        value = field,
        onValueChange = { value ->
            field = value
            val raw = value.text
            if (guarded) {
                when (val edit = SearchKeywords.interpret(query, raw)) {
                    is ChipEdit.Text -> onQueryChange(edit.rest)
                    ChipEdit.Remove -> onChipBackspace()
                }
            } else {
                // A pasted zero-width character would otherwise ride along into URLs, the history and the calculator.
                onQueryChange(SearchKeywords.stripZeroWidth(raw))
            }
        },
        singleLine = true,
        textStyle = textStyle.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = if (onSearch != null) KeyboardActions(onSearch = { onSearch() }) else KeyboardActions.Default,
        modifier = modifier
            .fillMaxWidth()
            .padding(padding)
            .onFocusChanged { focused = it.isFocused }
            // The visible placeholder is hidden from the tree below; this carries it instead.
            .semantics { contentDescription = placeholder },
        decorationBox = { field ->
            val hint: @Composable () -> Unit = {
                if (query.isEmpty() && !guarded) {
                    MetroText(
                        placeholder,
                        textStyle,
                        color = colors.subtle,
                        maxLines = 1,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                }
            }
            if (slim) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // Grows with the font scale instead of clipping the text.
                        .heightIn(min = 40.dp)
                        .background(colors.control.copy(alpha = 0.6f))
                        .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f).padding(start = if (guarded) 6.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (guarded) {
                            chip?.invoke()
                            Spacer(Modifier.width(8.dp))
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            hint()
                            field()
                        }
                    }
                    Box(
                        Modifier
                            .padding(horizontal = 12.dp)
                            .size(18.dp)
                            .drawBehind { drawMagnifier(if (focused) colors.accent else colors.subtle) },
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(2.dp, if (query.isEmpty()) colors.subtle else colors.accent)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    hint()
                    field()
                }
            }
        },
    )
    }
}

/** Hands on copied text without zero-width characters (the field's guard); everything else passes through. */
private class GuardFreeClipboard(private val base: Clipboard) : Clipboard by base {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val data = clipEntry?.clipData
        val text = if (data != null && data.itemCount == 1) data.getItemAt(0).text?.toString() else null
        val clean = text?.let(SearchKeywords::stripZeroWidth)
        base.setClipEntry(if (clean != null && clean != text) ClipEntry(ClipData.newPlainText(data!!.description.label, clean)) else clipEntry)
    }
}

/** A hairline magnifying glass filling the draw area. */
fun DrawScope.drawMagnifier(color: Color) {
    val stroke = 1.5.dp.toPx()
    val r = size.width * 0.32f
    val c = Offset(size.width * 0.42f, size.height * 0.42f)
    drawCircle(color, r, c, style = Stroke(stroke))
    val from = c + Offset(r, r) * 0.72f
    drawLine(color, from, Offset(size.width * 0.92f, size.height * 0.92f), stroke, StrokeCap.Round)
}
