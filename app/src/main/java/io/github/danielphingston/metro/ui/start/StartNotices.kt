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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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

/** What can happen to the pill; see [NoticeState.reduce]. */
internal sealed interface NoticeEvent {
    /** An edit was recorded. */
    data class Edit(val label: String) : NoticeEvent
    data class Switched(val name: String, val previous: Long) : NoticeEvent
    /** Edit mode ended; [undoNext] is what an undo would still take back, if anything. */
    data class EditModeLeft(val undoNext: String?) : NoticeEvent
    /** The pill was tapped and one step undone; [undoNext] is what the next tap would take back. */
    data class UndoTapped(val undoNext: String?) : NoticeEvent
    data object PageChanged : NoticeEvent
    /** The launcher was left (stopped), e.g. by launching an app. */
    data object Left : NoticeEvent
    /** The display time ran out. */
    data object Timeout : NoticeEvent
}

/**
 * The pill's whole behaviour as a pure reducer. Every edit, a layout switch, leaving edit mode
 * with something to undo, and an undo that leaves more steps all show it for [StartNotices.NOTICE_MILLIS]
 * (a new one restarts the time). The timeout, another page and leaving the launcher hide it.
 */
internal data class NoticeState(val current: StartNotice? = null, val serial: Long = 0) {
    private fun show(label: String) = copy(current = StartNotice.Edited(label, serial + 1), serial = serial + 1)

    fun reduce(event: NoticeEvent): NoticeState = when (event) {
        is NoticeEvent.Edit -> show(event.label)
        is NoticeEvent.Switched -> copy(current = StartNotice.Switched(event.name, event.previous, serial + 1), serial = serial + 1)
        is NoticeEvent.EditModeLeft -> event.undoNext?.let(::show) ?: this
        is NoticeEvent.UndoTapped -> event.undoNext?.let(::show) ?: copy(current = null)
        NoticeEvent.PageChanged, NoticeEvent.Left, NoticeEvent.Timeout -> copy(current = null)
    }

    companion object {
        /**
         * Whether the undo steps go once the pill is gone: always outside edit mode. Inside it
         * they stay, so leaving edit mode can offer the latest one for a last few seconds.
         */
        fun clearsUndoWhenHidden(editing: Boolean): Boolean = !editing
    }
}

/**
 * The pill's state: the latest notice until its time runs out ([NOTICE_MILLIS], longer when an
 * accessibility service asks for it), a new one replaces it, or the user goes to another page
 * or leaves the launcher (which launching an app also does).
 */
@Stable
internal class StartNotices {
    private var state by mutableStateOf(NoticeState())

    val current: StartNotice? get() = state.current

    fun send(event: NoticeEvent) {
        state = state.reduce(event)
    }

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
    editing: Boolean,
): StartNotices {
    val notices = remember { StartNotices() }
    val editingNow by rememberUpdatedState(editing)
    // Drops the undo steps once the pill is gone outside edit mode; see NoticeState.clearsUndoWhenHidden.
    fun hide(event: NoticeEvent) {
        notices.send(event)
        if (NoticeState.clearsUndoWhenHidden(editingNow)) viewModel.clearUndo()
    }
    LaunchedEffect(viewModel) {
        viewModel.undoRecorded.collect { notices.send(NoticeEvent.Edit(it.label)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.layoutSwitches.collect { notices.send(NoticeEvent.Switched(it.name, it.previous)) }
    }
    var wasEditing by remember { mutableStateOf(editing) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing) notices.send(NoticeEvent.EditModeLeft(viewModel.undoNext.value))
        wasEditing = editing
    }
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(notices.current) {
        if (notices.current == null) return@LaunchedEffect
        val millis = accessibility?.calculateRecommendedTimeoutMillis(
            StartNotices.NOTICE_MILLIS, containsIcons = true, containsText = true, containsControls = true,
        ) ?: StartNotices.NOTICE_MILLIS
        delay(millis)
        hide(NoticeEvent.Timeout)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { hide(NoticeEvent.Left) }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) hide(NoticeEvent.PageChanged) }
    return notices
}

/**
 * The one undo control: a small floating pill near the top of start, over the tiles, with an
 * undo arrow and the word. In edit mode it stays while something can be undone. Outside it, it
 * shows for a few seconds after an edit, after leaving edit mode with something left to undo,
 * and after a layout switch ("back"); see [NoticeState] and [pillContent].
 * Each tap takes back one step and, while more are left, keeps it up a few seconds longer.
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
    val shown = pillContent(notices.current, editing, undoNext)
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
                .clip(CircleShape)
                .background(colors.chrome)
                .clickable(onClickLabel = current.description, role = Role.Button) {
                    when (val n = current.notice) {
                        is StartNotice.Switched -> {
                            viewModel.switchLayout(n.previous)
                            notices.send(NoticeEvent.UndoTapped(null))
                        }
                        else -> {
                            viewModel.undo()
                            notices.send(NoticeEvent.UndoTapped(viewModel.undoNext.value))
                        }
                    }
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
internal data class PillContent(val label: String, val description: String, val notice: StartNotice?)

/**
 * What the pill shows, or null for no pill: "back" for a layout switch notice; "undo" while
 * editing with a step to take back ([undoNext]), or for an edit notice whose step is still there.
 */
internal fun pillContent(notice: StartNotice?, editing: Boolean, undoNext: String?): PillContent? = when {
    notice is StartNotice.Switched -> PillContent("back", "back to the previous layout", notice)
    undoNext == null -> null
    editing || notice is StartNotice.Edited -> PillContent("undo", editUndoDescription(undoNext)!!, notice)
    else -> null
}

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
