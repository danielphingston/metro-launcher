package io.github.danielphingston.metro.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.LabeledSlider
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.search.OtherSearchApp
import io.github.danielphingston.metro.ui.search.SearchInApps
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks the search engine the search overlay's "web" row uses, how far to pull down to open search,
 * and which smart search features (Metro Plus) are on. [onOpenMetroPlus] leads to the purchase.
 */
@Composable
internal fun SearchSettingsSection(
    settings: LauncherSettings,
    repository: SettingsRepository,
    onOpenMetroPlus: () -> Unit = {},
    onOpenPage: (SettingsPage) -> Unit = {},
) {
    val scope = rememberCoroutineScope()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchEngine.entries.forEach { engine ->
            MetroChoice(
                engine.label,
                settings.searchEngine == engine,
                { scope.launch { repository.setSearchEngine(engine) } },
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    LabeledSlider(
        "Pull distance",
        settings.pullDistance,
        LauncherSettings.MIN_PULL_DISTANCE..LauncherSettings.MAX_PULL_DISTANCE,
        { "$it dp" },
    ) { repository.setPullDistance(it) }
    Spacer(Modifier.height(10.dp))
    MetroText(
        "Pull down on start and let go to search apps and the web. Web searches open in your default " +
            "browser; Metro sends nothing while you type.",
        MetroType.caption,
        color = Metro.colors.subtle,
    )

    val context = LocalContext.current
    val purchases = (context.applicationContext as MetroApplication).purchases
    val unlocked by purchases.quickTilesUnlocked.collectAsState()
    val apps by (context.applicationContext as MetroApplication).apps.apps.collectAsState()
    val installed = apps.orEmpty().mapTo(HashSet()) { it.key.packageName }
    SmartSearchSection(
        settings, repository, unlocked, onOpenMetroPlus,
        assistantReady = AssistantChoice.resolve(settings.assistant, installed) != null,
        onOpenSearchInApps = { onOpenPage(SettingsPage.SEARCH_IN_APPS) },
        onOpenKeywords = { onOpenPage(SettingsPage.SEARCH_KEYWORDS) },
    )
}
