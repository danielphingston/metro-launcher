package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Picks the search engine the search overlay's "web" row uses, and how far to pull down to open search. */
@Composable
fun SearchSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()

    Section("search")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchEngine.entries.forEach { engine ->
            MetroChoice(
                engine.label,
                settings.searchEngine == engine,
                { scope.launch { repository.setSearchEngine(engine) } },
            )
        }
    }

    // The slider follows the finger; the setting is saved when it lets go.
    val range = LauncherSettings.MAX_PULL_DISTANCE - LauncherSettings.MIN_PULL_DISTANCE
    var dragged by remember { mutableStateOf<Float?>(null) }
    val fraction = dragged ?: ((settings.pullDistance - LauncherSettings.MIN_PULL_DISTANCE).toFloat() / range)
    val distance = LauncherSettings.MIN_PULL_DISTANCE + (fraction * range).roundToInt()
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MetroText("Pull distance", MetroType.body, modifier = Modifier.weight(1f))
        MetroText("$distance dp", MetroType.body, color = Metro.colors.accent)
    }
    MetroSlider(
        value = fraction,
        onValueChange = { dragged = it },
        modifier = Modifier.padding(top = 2.dp),
        onValueChangeFinished = {
            dragged?.let {
                scope.launch {
                    repository.setPullDistance(distance)
                    dragged = null
                }
            }
        },
    )
    Spacer(Modifier.height(10.dp))
    MetroText(
        "Pull down on start and let go to search apps and the web. Web searches open in your default " +
            "browser; Metro sends nothing while you type.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
}
