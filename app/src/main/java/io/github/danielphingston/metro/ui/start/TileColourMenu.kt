package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.TileColors
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroAccents

/**
 * The colour of one tile, in the customise sheet: follow the setting ("default"), the accent, the
 * colour of its icon (where it has one), or one of the twenty accent colours.
 */
@Composable
internal fun TileColourChoices(spec: TileSpec, onPick: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetroChoice("default", spec.color == null, { onPick(null) }, Modifier.weight(1f))
            MetroChoice("accent", spec.color == TileColors.ACCENT, { onPick(TileColors.ACCENT) }, Modifier.weight(1f))
            if (offersIconColour(spec)) {
                MetroChoice("from icon", spec.color == TileColors.ICON, { onPick(TileColors.ICON) }, Modifier.weight(1f))
            }
        }
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
                            .clickable(role = Role.RadioButton) { onPick(value) }
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
