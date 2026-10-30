package io.github.danielphingston.metro.ui.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.IconCache
import io.github.danielphingston.metro.data.TileIcon
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The search keyword chip at the start of the search box: the target's [icon] and [label] on the accent. */
@Composable
internal fun KeywordChip(label: String, icon: SearchIcon, icons: IconCache, modifier: Modifier = Modifier) {
    val colors = Metro.colors
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(colors.accent)
            .padding(horizontal = 8.dp)
            .semantics { contentDescription = "searching $label" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeywordIcon(icon, icons, CHIP_ICON, colors.onSolidAccent)
        Spacer(Modifier.width(6.dp))
        MetroText(label, MetroType.body, color = colors.onSolidAccent, maxLines = 1, modifier = Modifier.clearAndSetSemantics { })
    }
}

/** The icon size inside the chip. */
internal val CHIP_ICON = 20.dp

/** A [SearchIcon] at [size]: an app's own icon (coloured), a drawing in [tint], or a picture. */
@Composable
internal fun KeywordIcon(icon: SearchIcon, icons: IconCache, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    when (icon) {
        is SearchIcon.App -> {
            val px = with(LocalDensity.current) { size.roundToPx() }
            val cached = icons.peek(icon.app, px, false)
            val loaded by produceState<TileIcon?>(cached, icon.app.key, px, icons.generation) {
                value = icons.peek(icon.app, px, false) ?: withContext(Dispatchers.Default) { icons.load(icon.app, px, false) }
            }
            Box(modifier.size(size)) {
                loaded?.let { Image(it.bitmap, null, Modifier.size(size), contentScale = ContentScale.Fit) }
            }
        }
        is SearchIcon.Glyph -> Box(modifier.size(size).padding(size * 0.1f).drawBehind { icon.draw(this, tint) })
        is SearchIcon.Image -> Image(icon.bitmap, null, modifier.size(size), contentScale = ContentScale.Crop)
    }
}
