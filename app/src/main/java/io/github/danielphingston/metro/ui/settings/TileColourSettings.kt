package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconColor
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.TileColourMode
import io.github.danielphingston.metro.ui.components.LabeledSlider
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** "tile colour" on the start page: what tiles without a colour of their own use, and how vivid icon colours are. */
@Composable
internal fun TileColourSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    Section("tile colour")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TileColourMode.entries.forEach { mode ->
            MetroChoice(mode.label, settings.tileColourMode == mode, { scope.launch { repository.setTileColourMode(mode) } })
        }
    }
    Spacer(Modifier.height(8.dp))
    MetroText(
        "Tiles can also get their own colour: select a tile in edit mode and choose tile colour.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
    // Always shown: single tiles can ask for "from icon" whatever the mode is.
    Spacer(Modifier.height(16.dp))
    LabeledSlider(
        "Icon colour vividness",
        settings.tileIconVividness,
        IconColor.MIN_VIVIDNESS..IconColor.MAX_VIVIDNESS,
        { "$it%" },
    ) { repository.setTileIconVividness(it) }
}
