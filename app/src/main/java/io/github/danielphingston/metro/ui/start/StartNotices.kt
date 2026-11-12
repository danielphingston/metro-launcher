package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.danielphingston.metro.ui.LauncherViewModel
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.delay

/** What the slim bar along the bottom of start reports, and the one action it offers. */
internal sealed interface StartNotice {
    /** Bumped for every notice, so the same text twice in a row still restarts the bar's timer. */
    val serial: Long
    val message: String
    val action: String

    /** An edit was recorded; the action undoes it. */
    data class Edited(override val message: String, override val serial: Long) : StartNotice {
        override val action get() = "undo"
    }
}

/**
 * The bar's state: the latest notice until it times out ([NOTICE_MILLIS], longer when an
 * accessibility service asks for it), a new one replaces it, or the user moves on (leaves the
 * launcher, which launching an app also does, or swipes to another page).
 */
@Stable
internal class StartNotices {
    var current by mutableStateOf<StartNotice?>(null)
        private set
    private var serial = 0L

    fun show(make: (serial: Long) -> StartNotice) {
        current = make(++serial)
    }

    fun dismiss() {
        current = null
    }

    /**
     * Whether a bar covers the bottom of start: the latest notice, or in edit mode the way to undo
     * one more step while [undoNext] says there is one.
     */
    fun barShown(editing: Boolean, undoNext: String?): Boolean = current != null || (editing && undoNext != null)

    companion object {
        const val NOTICE_MILLIS = 6_000L
    }
}

@Composable
internal fun rememberStartNotices(viewModel: LauncherViewModel, isCurrentPage: Boolean): StartNotices {
    val notices = remember { StartNotices() }
    LaunchedEffect(viewModel) {
        viewModel.undoRecorded.collect { label -> notices.show { StartNotice.Edited(label, it) } }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notices.current) {
        if (notices.current == null) return@LaunchedEffect
        val millis = accessibility?.calculateRecommendedTimeoutMillis(
            StartNotices.NOTICE_MILLIS, containsIcons = false, containsText = true, containsControls = true,
        ) ?: StartNotices.NOTICE_MILLIS
        delay(millis)
        notices.dismiss()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { notices.dismiss() }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) notices.dismiss() }
    return notices
}

/**
 * The slim bar along the bottom of start, above the navigation bar, like the grouping bar:
 * "<what happened>" and its action. In edit mode it stays while there is anything to undo, so
 * several steps can be taken back in a row. TalkBack reads each new message as it appears.
 */
@Composable
internal fun StartNoticeBar(
    notices: StartNotices,
    editing: Boolean,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    val undoNext by viewModel.undoNext.collectAsStateWithLifecycle()
    val notice = notices.current
    // An edit notice whose step was already undone (from the edit mode bar, say) has nothing left to offer.
    val shown: Pair<String, String>? = when {
        notice != null && (notice !is StartNotice.Edited || undoNext != null) -> notice.message to notice.action
        editing && undoNext != null -> undoNext!! to "undo"
        else -> null
    }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 },
        exit = fadeOut(tween(120)),
    ) {
        val (message, action) = shown ?: last ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .windowInsetsPadding(WindowInsets.navigationBars),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroText(
                message,
                MetroType.body,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 20.dp, end = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            Box(
                Modifier
                    .widthIn(min = 96.dp)
                    .height(GroupingBarHeight)
                    .clickable(role = Role.Button) {
                        when (notice) {
                            is StartNotice.Edited, null -> {
                                viewModel.undo()
                                // In edit mode the bar stays on to offer the step before.
                                notices.dismiss()
                            }
                        }
                    }
                    .semantics { contentDescription = "$action: $message" }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                MetroText(action, MetroType.button, maxLines = 1)
            }
        }
    }
}
