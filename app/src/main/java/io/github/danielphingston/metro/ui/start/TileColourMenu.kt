package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.TileColors
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroAccents

/**
 * The colour of one tile: follow the setting, the accent, the colour of its icon (where it has
 * one), or one of the twenty accent colours. The current choice is marked like in the style menu.
 */
@Composable
internal fun TileColourMenu(spec: TileSpec, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    fun pick(color: String?) {
        onPick(color)
        onDismiss()
    }
    fun mark(label: String, on: Boolean) = if (on) "• $label" else label
    val items = listOfNotNull(
        mark("default", spec.color == null) to { pick(null) },
        mark("accent", spec.color == TileColors.ACCENT) to { pick(TileColors.ACCENT) },
        if (offersIconColour(spec)) mark("from icon", spec.color == TileColors.ICON) to { pick(TileColors.ICON) } else null,
    )
    MetroFlyout(onDismiss) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .background(Metro.colors.chrome),
        ) {
            MetroMenu(items)
            // Same 24dp margins as the menu entries above; the chrome runs under the navigation bar and
            // the last row clears it with normal spacing, so it never sits under the gesture handle.
            Column(
                Modifier.padding(
                    start = 24.dp, end = 24.dp, top = 8.dp,
                    bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetroAccents.chunked(5).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEach { (name, color) ->
                            val value = TileColors.fixed(color.toArgb())
                            val selected = spec.color == value
                            Box(
                                Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .metroTilt()
                                    .then(if (selected) Modifier.border(2.dp, Metro.colors.foreground) else Modifier)
                                    .padding(if (selected) 4.dp else 0.dp)
                                    .background(color)
                                    .clickable(role = Role.RadioButton) { pick(value) }
                                    .semantics {
                                        contentDescription = name
                                        this.selected = selected
                                    },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Whether the colour entry is offered for [spec]: every tile that draws the accent tile surface. */
internal fun offersTileColour(spec: TileSpec): Boolean =
    (spec.content as? io.github.danielphingston.metro.data.TileContent.Widget)?.transparent != true
