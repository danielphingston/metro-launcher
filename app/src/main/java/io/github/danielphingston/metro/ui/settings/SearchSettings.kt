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
fun SearchSettingsSection(settings: LauncherSettings, repository: SettingsRepository, onOpenMetroPlus: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    // The keyword editor takes the place of the page while a keyword is added or edited.
    var editing by remember { mutableStateOf<KeywordEdit?>(null) }
    BackHandler(enabled = editing != null) { editing = null }
    editing?.let { edit ->
        val context = LocalContext.current
        val apps by (context.applicationContext as MetroApplication).apps.apps.collectAsState()
        val otherSearchApps by produceState(emptyList<OtherSearchApp>(), apps) {
            value = withContext(Dispatchers.Default) { SearchInApps.otherApps(context, apps.orEmpty()) }
        }
        SearchKeywordEditor(
            original = edit.original,
            keywords = settings.searchKeywords,
            otherApps = otherSearchApps,
            onSave = { saved ->
                scope.launch {
                    val list = settings.searchKeywords
                    repository.setSearchKeywords(
                        if (edit.original == null) list + saved else list.map { if (it == edit.original) saved else it },
                    )
                }
                editing = null
            },
            onDelete = {
                scope.launch { repository.setSearchKeywords(settings.searchKeywords.filter { it != edit.original }) }
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

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
    // Which other apps accept a search is a package manager query: ask off the main thread, once the apps are known.
    val otherSearchApps by produceState(emptyList<OtherSearchApp>(), apps, unlocked) {
        value = if (unlocked) withContext(Dispatchers.Default) { SearchInApps.otherApps(context, apps.orEmpty()) } else emptyList()
    }
    SmartSearchSection(
        settings, repository, unlocked, onOpenMetroPlus,
        assistantReady = AssistantChoice.resolve(settings.assistant, installed) != null,
        otherSearchApps = otherSearchApps,
        apps = apps.orEmpty(),
        icons = (context.applicationContext as MetroApplication).icons,
        onEditKeyword = { editing = it },
    )
}
