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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.first

/** Where a hint shows: start, the app list, or start in edit mode. */
internal enum class HintPlace { START, APP_LIST, EDITING }

/**
 * What the hints watch on the launcher screen; [LauncherScreen] provides it from its own state.
 * Snapshot state, so [awaitSeen] waits without polling.
 */
internal interface HintSignals {
    val onAppList: Boolean
    val editing: Boolean
    val searching: Boolean

    /** Where the user is for the hints; null while search or a menu is over the screen. */
    val place: HintPlace?
}

/**
 * The hints after the first-run setup. The START ones show one at a time on start and go once
 * their gesture is done; the others show the first time their place is visited and go once it is
 * left. Any of them also goes when tapped. Stored as bits by ordinal, per install: append new
 * ones at the end, never reorder or remove.
 */
internal enum class CoachMark(val text: String, val place: HintPlace) {
    HOLD_TILE("Press and hold a tile to move, resize or remove it.", HintPlace.START),
    SWIPE_FOR_APPS("Swipe left for all your apps.", HintPlace.START),
    PULL_TO_SEARCH("Pull the start screen down to search.", HintPlace.START),
    APP_LIST("Tap a letter to jump. Hold an app to pin it.", HintPlace.APP_LIST),
    EDITING("Drag a tile to move it. Its corner buttons resize or unpin it.", HintPlace.EDITING),
    ;

    val bit: Int get() = 1 shl ordinal

    /** Waits until the user has done what this hint is about; see the class comment. */
    suspend fun awaitSeen(signals: HintSignals) {
        when (this) {
            HOLD_TILE -> until { signals.editing }
            SWIPE_FOR_APPS -> until { signals.onAppList }
            PULL_TO_SEARCH -> until { signals.searching }
            APP_LIST -> {
                until { signals.onAppList }
                until { !signals.onAppList }
            }
            EDITING -> {
                until { signals.editing }
                until { !signals.editing }
            }
        }
    }

    companion object {
        /** Every hint, as the setup leaves them when it is done. */
        val ALL: Int = entries.fold(0) { bits, mark -> bits or mark.bit }

        /** The hints still to show for the [pending] bits; bits of hints this version doesn't know are ignored. */
        fun pending(pending: Int): List<CoachMark> = entries.filter { pending and it.bit != 0 }

        /** The hint to show at [place] for the [pending] bits, or none. */
        fun next(pending: Int, place: HintPlace?): CoachMark? =
            place?.let { at -> entries.firstOrNull { it.place == at && pending and it.bit != 0 } }
    }
}

/** Suspends until [condition], a read of snapshot state, holds; no polling. */
private suspend fun until(condition: () -> Boolean) {
    snapshotFlow(condition).first { it }
}

/**
 * [mark] as a bar over the screen, a tap puts it away: at the bottom, above the navigation bar,
 * except in edit mode, where the tiles' corner buttons are, at the top below the status bar.
 */
@Composable
internal fun BoxScope.CoachMarkBar(mark: CoachMark, onDismiss: () -> Unit) {
    val colors = Metro.colors
    val atTop = mark.place == HintPlace.EDITING
    Box(
        Modifier
            .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
            .then(if (atTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
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
