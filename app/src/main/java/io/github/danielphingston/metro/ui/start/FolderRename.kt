package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * A text field over a dimmed start screen that renames a folder, styled like the app list's search
 * box. Done on the keyboard or a tap outside keeps the new name; back discards it.
 */
@Composable
internal fun FolderRename(name: String, onDone: (String) -> Unit) {
    val colors = Metro.colors
    var value by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    MetroFlyout(onDismiss = { onDone(value.text) }) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 96.dp),
        ) {
            MetroText("FOLDER NAME", MetroType.appName, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MetroType.listItem.copy(color = colors.foreground),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { field ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .background(colors.control.copy(alpha = 0.6f))
                            .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.text.isEmpty()) MetroText("folder", MetroType.listItem, color = colors.subtle, maxLines = 1)
                        field()
                    }
                },
            )
        }
    }
}
