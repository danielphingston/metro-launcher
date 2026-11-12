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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.draw.clip
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

/** What the undo pill at the top of start stands for after an edit or a layout switch, and its action. */
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

    /** Whether the undo pill is up: a notice, or in edit mode while [undoNext] says there is a step to take back. */
    fun pillShown(editing: Boolean, undoNext: String?): Boolean = current != null || (editing && undoNext != null)

    companion object {
        const val NOTICE_MILLIS = 4_000L
    }
}

/** The undo pill's TalkBack label in edit mode, or null when there is nothing to undo (no pill). */
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
 * The one undo control: a small floating pill near the top of start, over the tiles, with an
 * undo arrow and the word. It shows
 * - for a short while after a destructive edit or a layout switch ([StartNotices.current]),
 *   until the user moves on, as the bar used to;
 * - all the time in edit mode while a step can be taken back ([undoNext]), one step per tap.
 * TalkBack hears what the tap takes back.
 */
@Composable
internal fun UndoPill(
    notices: StartNotices,
    viewModel: LauncherViewModel,
    editing: Boolean,
    modifier: Modifier = Modifier,
) {
    val undoNext by viewModel.undoNext.collectAsStateWithLifecycle()
    val notice = notices.current
    // An edit notice whose step was already undone has nothing left to offer.
    val fromNotice: StartNotice? = notice?.takeIf { it !is StartNotice.Edited || undoNext != null }
    val shown: PillContent? = when {
        fromNotice is StartNotice.Switched -> PillContent("back", "back to the previous layout", fromNotice)
        fromNotice != null || (editing && undoNext != null) -> undoNext?.let { PillContent("undo", "undo $it", fromNotice) }
        else -> null
    }
    var last by remember { mutableStateOf(shown) }
    if (shown != null) last = shown
    AnimatedVisibility(
        visible = shown != null,
        modifier = modifier,
        enter = fadeIn(tween(160)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { -it / 2 },
        exit = fadeOut(tween(120)),
    ) {
        val current = shown ?: last ?: return@AnimatedVisibility
        val colors = Metro.colors
        Row(
            Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 12.dp)
                .onGloballyPositioned { notices.barBounds = it.boundsInRoot() }
                .clip(CircleShape)
                .background(colors.chrome)
                .clickable(onClickLabel = current.description, role = Role.Button) {
                    when (val n = current.notice) {
                        is StartNotice.Switched -> viewModel.switchLayout(n.previous)
                        else -> viewModel.undo()
                    }
                    notices.send(NoticeEvent.UndoTapped)
                }
                .semantics {
                    contentDescription = current.description
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(start = 14.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp).drawBehind { drawUndoArrow(colors.accent, 2.dp.toPx()) })
            MetroText(current.label, MetroType.button, color = colors.accent, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** What the pill says ([label]), what TalkBack says ([description]), and the notice it stands for, if any. */
private data class PillContent(val label: String, val description: String, val notice: StartNotice?)

/** A hairline arrow curving back to the left, like the other round buttons' glyphs. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawUndoArrow(color: androidx.compose.ui.graphics.Color, stroke: Float) {
    val w = size.width
    val tip = Offset(w * 0.22f, w * 0.40f)
    val shaft = Path().apply {
        moveTo(tip.x, tip.y)
        cubicTo(w * 0.52f, w * 0.18f, w * 0.92f, w * 0.34f, w * 0.80f, w * 0.80f)
    }
    val head = Path().apply {
        moveTo(tip.x, w * 0.12f)
        lineTo(tip.x, tip.y)
        lineTo(w * 0.50f, tip.y + w * 0.02f)
    }
    val style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(shaft, color, style = style)
    drawPath(head, color, style = style)
}
