package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * A settings page that belongs to Metro Plus: while the pack is locked it shows what the feature
 * is and the way to the purchase instead of [content], like the smart search pages do.
 */
@Composable
internal fun PlusGate(about: String, onOpenMetroPlus: () -> Unit, content: @Composable () -> Unit) {
    val app = LocalContext.current.applicationContext as? MetroApplication
    val unlocked = app?.purchases?.quickTilesUnlocked?.collectAsState()?.value ?: true
    if (unlocked) {
        content()
        return
    }
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        LockGlyph(Metro.colors.subtle, Modifier.padding(top = 4.dp).size(width = 20.dp, height = 22.dp))
        Column(Modifier.weight(1f)) {
            MetroText("a Metro Plus feature", MetroType.body)
            MetroText(about, MetroType.caption, color = Metro.colors.subtle)
        }
    }
    Spacer(Modifier.height(12.dp))
    MetroButton("unlock with Metro Plus", onClick = onOpenMetroPlus)
}
