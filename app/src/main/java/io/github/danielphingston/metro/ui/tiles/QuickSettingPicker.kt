package io.github.danielphingston.metro.ui.tiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.ui.ResolvedTile
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

/** Flyout panel listing the paid tiles with live previews. While locked, choosing one offers the purchase instead. */
@Composable
fun QuickSettingPicker(viewModel: LauncherViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val unlocked by viewModel.quickTilesUnlocked.collectAsStateWithLifecycle()
    val price by viewModel.quickTilesPrice.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val pkg = AssistantChoice.resolve(settings.assistant, apps.mapTo(HashSet()) { it.key.packageName })
    val app = pkg?.let { p -> apps.firstOrNull { it.key.packageName == p && it.key.userSerial == 0L } ?: apps.firstOrNull { it.key.packageName == p } }

    fun buy() {
        if (price == null) return
        context.findActivity()?.let(viewModel::buyQuickTiles)
    }

    BackHandler(onBack = onDismiss)
    MetroFlyout(onDismiss) {
        PaidTilesPanel(
            unlocked = unlocked,
            price = price,
            assistantApp = app,
            icons = viewModel.icons,
            monochrome = settings.monochromeIcons,
            onBuy = ::buy,
            onAdd = { content, size ->
                viewModel.addTile(content, size)
                onDismiss()
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** The picker's panel without the view model: a "quick settings" section and an "assistant" section. */
@Composable
internal fun PaidTilesPanel(
    unlocked: Boolean,
    price: String?,
    assistantApp: AppInfo?,
    icons: IconCache,
    monochrome: Boolean,
    onBuy: () -> Unit,
    onAdd: (TileContent, TileSize) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val assistantName = assistantApp?.let { AssistantChoice.displayName(it.key.packageName, it.label) }
    LazyColumn(
        modifier
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp)
            .fillMaxWidth()
            .background(Metro.colors.chrome)
            // Keeps taps on the panel from dismissing it.
            .clickable(enabled = false, onClick = {}),
        state = listState,
        contentPadding = PaddingValues(
            top = 20.dp,
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
        ),
    ) {
        item {
            MetroText("paid tiles", MetroType.pageTitle, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }
        if (!unlocked) {
            item {
                MetroText(
                    price?.let { "unlock paid tiles — $it" } ?: "not available yet",
                    MetroType.sectionTitle,
                    color = Metro.colors.accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (price != null) Modifier.metroTilt().clickable(onClickLabel = "unlock paid tiles", onClick = onBuy) else Modifier)
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        item { SectionTitle("quick settings") }
        items(QuickSettingKind.entries, key = { it.id }) { kind ->
            PickerRow(
                onClickLabel = if (unlocked) "add ${kind.label}" else "unlock paid tiles",
                onClick = { if (unlocked) onAdd(TileContent.QuickSetting(kind), TileSize.MEDIUM) else onBuy() },
                preview = { QuickSettingFace(kind, TileSize.SMALL, kind.label, PreviewSize, Modifier.size(PreviewSize)) },
                title = kind.label,
                subtitle = kind.action(),
            )
        }
        item { SectionTitle("assistant") }
        item {
            PickerRow(
                onClickLabel = if (unlocked) "add assistant tile" else "unlock paid tiles",
                onClick = { if (unlocked) onAdd(TileContent.Assistant, TileSize.WIDE) else onBuy() },
                preview = {
                    val tile = ResolvedTile(
                        TileSpec(0, TileSize.SMALL, TileContent.Assistant), assistantApp, assistantName ?: "assistant",
                    )
                    Box(Modifier.size(PreviewSize)) { AssistantTile(tile, null, false, monochrome, icons, PreviewSize) }
                },
                title = "assistant",
                subtitle = if (assistantName != null) {
                    "follows $assistantName: its latest reply and your last question. Change the app in settings."
                } else {
                    "choose an assistant app in settings"
                },
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    MetroText(
        text,
        MetroType.caption,
        color = Metro.colors.subtle,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun PickerRow(
    onClickLabel: String,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .metroTilt()
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview()
        Column(Modifier.weight(1f)) {
            MetroText(title, MetroType.listItem, maxLines = 1)
            MetroText(subtitle, MetroType.caption, color = Metro.colors.subtle, maxLines = 3)
        }
    }
}
