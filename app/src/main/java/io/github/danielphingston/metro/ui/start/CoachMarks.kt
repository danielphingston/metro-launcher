package io.github.danielphingston.metro.ui.start

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/**
 * The hints on the real start screen after the first-run setup, one at a time, in this order. Each
 * goes once its gesture is done (see [LauncherScreen]) or it is tapped away. Stored as bits by
 * ordinal, per install: append new ones at the end, never reorder or remove.
 */
internal enum class CoachMark(val text: String) {
    HOLD_TILE("Press and hold a tile to move, resize or remove it."),
    SWIPE_FOR_APPS("Swipe left for all your apps."),
    PULL_TO_SEARCH("Pull the start screen down to search."),
    ;

    val bit: Int get() = 1 shl ordinal

    companion object {
        /** Every hint, as the setup leaves them when it is done. */
        val ALL: Int = entries.fold(0) { bits, mark -> bits or mark.bit }

        /** The hint to show for the [pending] bits, or none; bits of hints this version doesn't know are ignored. */
        fun next(pending: Int): CoachMark? = entries.firstOrNull { pending and it.bit != 0 }
    }
}

/** [mark] as a bar at the bottom of start, above the navigation bar; a tap puts it away. */
@Composable
internal fun BoxScope.CoachMarkBar(mark: CoachMark, onDismiss: () -> Unit) {
    val colors = Metro.colors
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 16.dp)
            .fillMaxWidth()
            .background(colors.accent)
            .clickable(onClickLabel = "got it", onClick = onDismiss)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column {
            MetroText(mark.text, MetroType.body, color = Color.White)
            Spacer(Modifier.height(4.dp))
            MetroText("tap to dismiss", MetroType.caption, color = Color.White.copy(alpha = 0.75f))
        }
    }
}
