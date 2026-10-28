package io.github.danielphingston.metro.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Where the app list and the search results start from the screen's start edge. */
internal val ListStart = 20.dp

/** The square an app icon (or a glyph standing in for one) sits in. */
internal val IconBox = 44.dp

/**
 * A list row that tilts when pressed and reports its on-screen bounds to [onClick] and
 * [onLongClick], so the launch animation can grow from the row. [beforeTilt] adds modifiers
 * (the turnstile, say) that need the row's bounds; they sit outside the tilt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ClickableRow(
    onClick: (Rect?) -> Unit,
    onLongClick: ((Rect) -> Unit)?,
    modifier: Modifier = Modifier,
    beforeTilt: (bounds: () -> Rect?) -> Modifier = { Modifier },
    content: @Composable RowScope.() -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Row(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .then(beforeTilt { bounds })
            .metroTilt(maxDegrees = 6f)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = { onClick(bounds) },
                onLongClick = onLongClick?.let { long -> { bounds?.let(long) } },
                onLongClickLabel = if (onLongClick != null) "more options" else null,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
