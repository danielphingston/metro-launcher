package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.IconPackInfo
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.search.SearchGlyphs
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Cell = 56.dp

/**
 * Full-screen picker for a tile's icon: the app's white line glyphs, then the icons of every
 * installed icon pack. [onPick] gets "glyph:<id>" or "pack:<package>:<drawable>".
 */
@Composable
internal fun IconPicker(icons: IconCache, hasIcon: Boolean, onReset: () -> Unit, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val packs by produceState(emptyList<IconPackInfo>(), icons) {
        value = withContext(Dispatchers.IO) { icons.installedPacks() }
    }
    val glyphs = remember { SearchGlyphs.all() }
    val q = query.trim().lowercase()
    MetroFlyout(onDismiss, paneTitle = "choose icon") {
        Column(
            Modifier.fillMaxSize().background(Metro.colors.chrome).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            MetroText("choose icon", MetroType.sectionTitle, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            BasicTextField(
                query,
                { query = it },
                singleLine = true,
                textStyle = MetroType.body.copy(color = Metro.colors.foreground),
                cursorBrush = SolidColor(Metro.colors.foreground),
                modifier = Modifier.fillMaxWidth().background(Metro.colors.subtle.copy(alpha = 0.2f)).padding(12.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) MetroText("search", MetroType.body, color = Metro.colors.subtle)
                        inner()
                    }
                },
            )
            LazyColumn(Modifier.fillMaxSize()) {
                if (hasIcon) {
                    item {
                        MetroText(
                            "reset to default",
                            MetroType.menuItem,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = onReset).padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                item { MetroText("line icons", MetroType.sectionTitle, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                item {
                    val shown = glyphs.filter { q.isEmpty() || it.first.contains(q) }
                    // A LazyColumn can't nest a lazy grid with unbounded height; glyphs are few, so lay them out in rows.
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    ) {
                        shown.forEach { (id, glyph) ->
                            Box(
                                Modifier.size(Cell).background(Metro.colors.accent).clickable { onPick("glyph:$id") },
                                contentAlignment = Alignment.Center,
                            ) { GlyphPicture(glyph, 40.dp, Metro.colors.onAccent) }
                        }
                    }
                }
                packs.forEach { pack ->
                    item(key = pack.packageName) { PackSection(pack, icons, q, onPick) }
                }
            }
        }
    }
}

@Composable
private fun PackSection(pack: IconPackInfo, icons: IconCache, query: String, onPick: (String) -> Unit) {
    val names by produceState(emptyList<String>(), pack.packageName) {
        value = withContext(Dispatchers.IO) { icons.packIconNames(pack.packageName) }
    }
    val shown = remember(names, query) { names.filter { query.isEmpty() || it.lowercase().contains(query) } }
    if (shown.isEmpty()) return
    MetroText(pack.label, MetroType.sectionTitle, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    // Bounded height so the grid can sit in the column and still compose only the visible cells.
    LazyVerticalGrid(
        GridCells.Adaptive(Cell),
        Modifier.fillMaxWidth().heightIn(max = 320.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        items(shown, key = { it }) { name ->
            val icon = "pack:${pack.packageName}:$name"
            Box(
                Modifier.aspectRatio(1f).background(Metro.colors.accent).clickable { onPick(icon) },
                contentAlignment = Alignment.Center,
            ) { CustomTileIcon(icon, 40.dp, icons) }
        }
    }
}
