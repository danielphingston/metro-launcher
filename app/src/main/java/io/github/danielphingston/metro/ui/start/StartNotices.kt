package io.github.danielphingston.metro.ui.start

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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

    /** A destructive edit was recorded; the action undoes it. */
    data class Edited(override val message: String, override val serial: Long) : StartNotice {
        override val action get() = "undo"
    }

    /** Start switched to the layout [message]; the action goes back to layout [previous]. */
    data class Switched(override val message: String, val previous: Long, override val serial: Long) : StartNotice {
        override val action get() = "back"
    }
}

/** What can happen to the bar; see [NoticeState.reduce]. */
internal sealed interface NoticeEvent {
    /** An edit was recorded; only a [destructive] one gets a bar. */
    data class Edit(val label: String, val destructive: Boolean) : NoticeEvent
    data class Switched(val name: String, val previous: Long) : NoticeEvent
    /** A pointer went down on the launcher outside the bar. */
    data object Touch : NoticeEvent
    data object Scroll : NoticeEvent
    data object PageChanged : NoticeEvent
    /** The launcher was left (stopped), e.g. by launching an app. */
    data object Left : NoticeEvent
    /** The display time ran out. */
    data object Timeout : NoticeEvent
    /** The bar's action was tapped. */
    data object UndoTapped : NoticeEvent
}

/**
 * The bar's whole behaviour as a pure reducer. A destructive edit or a layout switch shows the
 * bar; a harmless edit, a touch elsewhere, a scroll, a page change, leaving the launcher, the
 * timeout and tapping the action all hide it.
 */
internal data class NoticeState(val current: StartNotice? = null, val serial: Long = 0) {
    fun reduce(event: NoticeEvent): NoticeState = when (event) {
        is NoticeEvent.Edit ->
            if (event.destructive) copy(current = StartNotice.Edited(event.label, serial + 1), serial = serial + 1)
            else copy(current = null)
        is NoticeEvent.Switched -> copy(current = StartNotice.Switched(event.name, event.previous, serial + 1), serial = serial + 1)
        NoticeEvent.Touch, NoticeEvent.Scroll, NoticeEvent.PageChanged, NoticeEvent.Left,
        NoticeEvent.Timeout, NoticeEvent.UndoTapped,
        -> copy(current = null)
    }
}

/**
 * The bar's state: the latest notice until it times out ([NOTICE_MILLIS], longer when an
 * accessibility service asks for it), a new one replaces it, or the user moves on (touches
 * anything else, scrolls, swipes to another page or leaves the launcher, which launching an app
 * also does).
 */
@Stable
internal class StartNotices {
    private var state by mutableStateOf(NoticeState())

    val current: StartNotice? get() = state.current

    /** Where the bar is on screen, so a touch on it is not a touch elsewhere; null while it is hidden. */
    var barBounds: Rect? = null

    /** Where the observing modifier sits on screen; its pointer positions are relative to that. */
    var origin: Offset = Offset.Zero

    fun send(event: NoticeEvent) {
        state = state.reduce(event)
    }

    fun dismiss() = send(NoticeEvent.Timeout)

    /**
     * Whether something covers the bottom of start: a notice, or in edit mode the undo button
     * while [undoNext] says there is a step to take back.
     */
    fun barShown(editing: Boolean, undoNext: String?): Boolean = current != null || (editing && undoNext != null)

    companion object {
        const val NOTICE_MILLIS = 4_000L
    }
}

/** The edit mode undo button's TalkBack label, or null when there is nothing to undo (no button). */
internal fun editUndoDescription(undoNext: String?): String? = undoNext?.let { "undo $it" }

@Composable
internal fun rememberStartNotices(
    viewModel: LauncherViewModel,
    isCurrentPage: Boolean,
    scrolling: () -> Boolean,
): StartNotices {
    val notices = remember { StartNotices() }
    LaunchedEffect(viewModel) {
        viewModel.undoRecorded.collect { notices.send(NoticeEvent.Edit(it.label, it.destructive)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.layoutSwitches.collect { notices.send(NoticeEvent.Switched(it.name, it.previous)) }
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notices.current) {
        if (notices.current == null) return@LaunchedEffect
        val millis = accessibility?.calculateRecommendedTimeoutMillis(
            StartNotices.NOTICE_MILLIS, containsIcons = false, containsText = true, containsControls = true,
        ) ?: StartNotices.NOTICE_MILLIS
        delay(millis)
        notices.send(NoticeEvent.Timeout)
    }
    LaunchedEffect(notices) {
        snapshotFlow(scrolling).collect { if (it) notices.send(NoticeEvent.Scroll) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { notices.send(NoticeEvent.Left) }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) notices.send(NoticeEvent.PageChanged) }
    return notices
}

/**
 * Hides the bar at the first pointer-down anywhere on the launcher but on the bar itself. It
 * watches before the children and consumes nothing, so the touch still does what it would have.
 */
internal fun Modifier.hideNoticesOnTouch(notices: StartNotices): Modifier = this
    .onGloballyPositioned { notices.origin = it.positionInRoot() }
    .pointerInput(notices) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (notices.current == null) continue
                val bar = notices.barBounds
                if (event.changes.any { it.changedToDown() && (bar == null || !bar.contains(notices.origin + it.position)) }) {
                    notices.send(NoticeEvent.Touch)
                }
            }
        }
    }

/**
 * The slim bar along the bottom of start, above the navigation bar, like the grouping bar:
 * "<what happened>" and its action. TalkBack reads each new message as it appears.
 */
@Composable
internal fun StartNoticeBar(
    notices: StartNotices,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    val undoNext by viewModel.undoNext.collectAsStateWithLifecycle()
    val notice = notices.current
    // An edit notice whose step was already undone (from the edit mode button, say) has nothing left to offer.
    val shown: StartNotice? = notice?.takeIf { it !is StartNotice.Edited || undoNext != null }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 2 },
        exit = fadeOut(tween(120)),
    ) {
        val current = shown ?: last ?: return@AnimatedVisibility
        Row(
            Modifier
                .fillMaxWidth()
                .background(Metro.colors.chrome)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .onGloballyPositioned { notices.barBounds = it.boundsInRoot() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroText(
                current.message,
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
                        when (current) {
                            is StartNotice.Edited -> viewModel.undo()
                            is StartNotice.Switched -> viewModel.switchLayout(current.previous)
                        }
                        notices.send(NoticeEvent.UndoTapped)
                    }
                    .semantics { contentDescription = "${current.action}: ${current.message}" }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                MetroText(current.action, MetroType.button, maxLines = 1)
            }
        }
    }
}

/**
 * Edit mode's own "undo": a round outlined button with an undo arrow and the word, along the
 * bottom of start where the grouping bar goes. It is there while a step can be taken back and
 * no notice covers the bottom (the notice's action does the same); each tap takes back one step.
 */
@Composable
internal fun EditUndoButton(
    visible: Boolean,
    viewModel: LauncherViewModel,
    modifier: Modifier = Modifier,
) {
    val undoNext by viewModel.undoNext.collectAsStateWithLifecycle()
    val description = editUndoDescription(undoNext)
    if (!visible || description == null) return
    val colors = Metro.colors
    val tint = colors.foreground.copy(alpha = 0.6f)
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .height(GroupingBarHeight)
            .clickable(onClickLabel = description, role = Role.Button) { viewModel.undo() }
            .semantics { contentDescription = description }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .border(1.5.dp, tint, CircleShape)
                .drawBehind { drawUndoArrow(tint, 1.5.dp.toPx()) },
        )
        MetroText("undo", MetroType.button, maxLines = 1, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A hairline arrow curving back to the left, like the other round buttons' glyphs. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUndoArrow(color: androidx.compose.ui.graphics.Color, stroke: Float) {
    val w = size.width
    val tip = Offset(w * 0.30f, w * 0.44f)
    val shaft = Path().apply {
        moveTo(tip.x, tip.y)
        cubicTo(w * 0.52f, w * 0.30f, w * 0.78f, w * 0.40f, w * 0.70f, w * 0.68f)
    }
    val head = Path().apply {
        moveTo(tip.x, w * 0.24f)
        lineTo(tip.x, tip.y)
        lineTo(w * 0.52f, tip.y + w * 0.02f)
    }
    val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(shaft, color, style = style)
    drawPath(head, color, style = style)
}
