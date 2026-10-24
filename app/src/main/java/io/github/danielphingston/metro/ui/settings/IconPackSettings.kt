package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconPackInfo
import io.github.danielphingston.metro.data.IconPacks
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks the icon pack used for tile icons, from the packs installed on the phone. */
@Composable
fun IconPackSettingsSection(settings: LauncherSettings, repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    val packs by produceState<List<IconPackInfo>?>(null) {
        value = withContext(Dispatchers.IO) { IconPacks(context).installed() }
    }

    Section("icon pack")
    packs?.let { installed ->
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MetroChoice("none (app icons)", settings.iconPack == null, {
                scope.launch { repository.setIconPack(null) }
            }, Modifier.fillMaxWidth())
            installed.forEach { pack ->
                MetroChoice(pack.label, settings.iconPack == pack.packageName, {
                    scope.launch { repository.setIconPack(pack.packageName) }
                }, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(10.dp))
        if (installed.isEmpty()) {
            MetroText("no icon packs installed", MetroType.caption, color = colors.subtle)
        }
        MetroText("Icon packs replace themed white icons.", MetroType.caption, color = colors.subtle)
    }
}
