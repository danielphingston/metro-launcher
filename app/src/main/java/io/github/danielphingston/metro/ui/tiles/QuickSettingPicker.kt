package io.github.danielphingston.metro.ui.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.findActivity
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

private val PreviewSize = 72.dp

/** What a tap on the tile of [kind] does, for the picker. */
private fun QuickSettingKind.action(): String = when (this) {
    QuickSettingKind.FLASHLIGHT, QuickSettingKind.DO_NOT_DISTURB, QuickSettingKind.AUTO_ROTATE -> "tap to switch on or off"
    QuickSettingKind.RINGER -> "tap to cycle ring, vibrate and silent"
    QuickSettingKind.BRIGHTNESS -> "tap to step through auto, 25%, 50% and 100%"
    else -> "tap to open the settings"
}

/** Flyout panel listing the quick-setting tiles with live previews. While locked, choosing one offers the purchase instead. */
@Composable
fun QuickSettingPicker(viewModel: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val unlocked by viewModel.quickTilesUnlocked.collectAsStateWithLifecycle()
    val price by viewModel.quickTilesPrice.collectAsStateWithLifecycle()

    fun buy() {
        if (price == null) return
        context.findActivity()?.let(viewModel::buyQuickTiles)
    }

    BackHandler(onBack = onDismiss)
    MetroFlyout(onDismiss) {
        LazyColumn(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                // Keeps taps on the panel from dismissing it.
                .clickable(enabled = false, onClick = {}),
            contentPadding = PaddingValues(
                top = 20.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                MetroText("quick settings", MetroType.pageTitle, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            if (!unlocked) {
                item {
                    MetroText(
                        price?.let { "unlock quick settings tiles — $it" } ?: "not available yet",
                        MetroType.sectionTitle,
                        color = Metro.colors.accent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (price != null) Modifier.metroTilt().clickable(onClickLabel = "unlock quick settings tiles", onClick = ::buy) else Modifier)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }
            items(QuickSettingKind.entries, key = { it.id }) { kind ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .metroTilt()
                        .clickable(onClickLabel = if (unlocked) "add ${kind.label}" else "unlock quick settings tiles") {
                            if (unlocked) {
                                viewModel.addTile(TileContent.QuickSetting(kind), TileSize.MEDIUM)
                                onDismiss()
                            } else {
                                buy()
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QuickSettingFace(kind, TileSize.SMALL, kind.label, PreviewSize, Modifier.size(PreviewSize))
                    Column(Modifier.weight(1f)) {
                        MetroText(kind.label, MetroType.listItem, maxLines = 1)
                        MetroText(kind.action(), MetroType.caption, color = Metro.colors.subtle, maxLines = 2)
                    }
                }
            }
        }
    }
}
