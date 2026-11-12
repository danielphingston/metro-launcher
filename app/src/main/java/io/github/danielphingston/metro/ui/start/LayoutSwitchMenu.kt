package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroMenu

/** The switch tiles the "layouts" entry of the add menu offers: "next layout", then one per saved layout. */
internal fun layoutSwitchEntries(layouts: LayoutSet?): List<Pair<String, TileContent.LayoutSwitch>> =
    listOf("next layout" to TileContent.LayoutSwitch(null)) +
        layouts?.layouts.orEmpty().map { it.name to TileContent.LayoutSwitch(it.id) }

/** The "layouts" submenu of the add menu; see [layoutSwitchEntries]. */
@Composable
internal fun LayoutSwitchMenu(layouts: LayoutSet?, onAdd: (TileContent.LayoutSwitch) -> Unit, onDismiss: () -> Unit) {
    MetroFlyout(onDismiss) {
        MetroMenu(
            items = layoutSwitchEntries(layouts).map { (label, content) -> label to { onAdd(content) } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        )
    }
}
