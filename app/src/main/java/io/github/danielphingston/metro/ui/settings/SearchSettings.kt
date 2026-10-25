package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** Picks the search engine the search overlay's "web" row uses. */
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
    Spacer(Modifier.height(10.dp))
    MetroText(
        "Swipe down on start to search apps and the web. Web searches open in your default " +
            "browser; Metro sends nothing while you type.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )
}
