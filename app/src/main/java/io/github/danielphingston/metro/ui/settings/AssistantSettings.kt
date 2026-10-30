package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.AssistantApps
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.data.ListFilter
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.SearchFieldStyle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** Lists longer than this get a filter field. */
internal const val FILTER_FROM = 8

/**
 * The shared assistant choice: off, one of the installed known assistants, or any launchable app
 * that takes shared text. The search row and the assistant tile both follow it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantChoiceControl(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps by (context.applicationContext as MetroApplication).apps.apps.collectAsState()
    val launchable = apps.orEmpty()
    val installed = launchable.mapTo(HashSet()) { it.key.packageName }
    val current = AssistantChoice.resolve(settings.assistant, installed)
    val others = remember(launchable) { AssistantApps.otherSendTargets(context, launchable) }
    val knownPackages = AssistantChoice.KNOWN.map { it.packageName }
    val currentIsOther = current != null && current !in knownPackages
    var showOthers by remember { mutableStateOf(false) }

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroChoice("off", current == null, { scope.launch { repository.setAssistant(null) } })
        AssistantChoice.installedKnown(installed).forEach { assistant ->
            MetroChoice(assistant.label, current == assistant.packageName, {
                showOthers = false
                scope.launch { repository.setAssistant(assistant.packageName) }
            })
        }
        MetroChoice(
            if (currentIsOther) "other: ${others.firstOrNull { it.key.packageName == current }?.label ?: current}" else "other app",
            currentIsOther || showOthers,
            { showOthers = !showOthers },
        )
    }
    if (showOthers) {
        Spacer(Modifier.height(8.dp))
        if (others.isEmpty()) {
            MetroText("No other app that accepts shared text was found.", MetroType.caption, color = Metro.colors.subtle)
        } else {
            // Every app that takes shared text can be listed here, which may be dozens: a filter past eight.
            var filter by remember { mutableStateOf("") }
            if (others.size > FILTER_FROM) {
                SearchField(filter, { filter = it }, placeholder = "filter apps", style = SearchFieldStyle.OUTLINED)
                Spacer(Modifier.height(8.dp))
            }
            val shown = others.filter { ListFilter.matches(filter, it.label) }
            if (shown.isEmpty()) MetroText("no app matches “${filter.trim()}”", MetroType.caption, color = Metro.colors.subtle)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { app ->
                    MetroChoice(app.label, current == app.key.packageName, {
                        showOthers = false
                        scope.launch { repository.setAssistant(app.key.packageName) }
                    })
                }
            }
        }
    }
}
