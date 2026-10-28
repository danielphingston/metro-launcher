package io.github.danielphingston.metro.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

@Composable
fun MetroText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Metro.colors.foreground,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * The Windows Phone "tilt" press effect: the element tips toward the finger, or sinks when
 * pressed in the middle. Observes pointer events without consuming them, so it composes with
 * any click or drag handling on the same element.
 *
 * Turning [enabled] off only stops the effect (and eases back to rest); the modifier chain keeps
 * its shape. Dropping the modifier instead would rebuild the element's pointer input chain, and
 * that cancels a gesture already running on it: a tile that goes into edit mode while pressed
 * would lose the drag that follows.
 */
fun Modifier.metroTilt(enabled: Boolean = true, maxDegrees: Float = 9f): Modifier = composed {
    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) {
        if (!enabled) {
            launch { rotY.animateTo(0f, tween(150)) }
            launch { rotX.animateTo(0f, tween(150)) }
            launch { scale.animateTo(1f, tween(150)) }
        }
    }
    this
        .pointerInput(maxDegrees) {
            fun animate(x: Float, y: Float, s: Float, millis: Int) {
                scope.launch { rotY.animateTo(x, tween(millis)) }
                scope.launch { rotX.animateTo(y, tween(millis)) }
                scope.launch { scale.animateTo(s, tween(millis)) }
            }
            awaitPointerEventScope {
                var down: androidx.compose.ui.geometry.Offset? = null
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                    val start = down
                    if (change.pressed && !change.previousPressed) {
                        down = change.position
                        val nx = (change.position.x / size.width - 0.5f) * 2 // -1..1
                        val ny = (change.position.y / size.height - 0.5f) * 2
                        val edge = maxOf(kotlin.math.abs(nx), kotlin.math.abs(ny)).coerceAtMost(1f)
                        if (currentEnabled) animate(nx * maxDegrees, -ny * maxDegrees, 1f - 0.04f * (1f - edge), 90)
                    } else if (start != null &&
                        (!change.pressed || (change.position - start).getDistance() > viewConfiguration.touchSlop)
                    ) {
                        // Released, or the finger started scrolling/dragging: spring back.
                        down = null
                        animate(0f, 0f, 1f, 150)
                    }
                }
            }
        }
        .graphicsLayer {
            rotationX = rotX.value
            rotationY = rotY.value
            scaleX = scale.value
            scaleY = scale.value
            cameraDistance = 14f * density
        }
}

/**
 * Windows 10 Mobile toggle switch: a rounded track with a round thumb. Off is an outline with
 * the thumb on the left; on is filled with the accent and the thumb on the right.
 */
@Composable
fun MetroToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit, description: String? = null) {
    val colors = Metro.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(remember { MutableInteractionSource() }, null) { onChange(!checked) }
            .semantics(mergeDescendants = true) {
                role = Role.Switch
                stateDescription = if (checked) "on" else "off"
            }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            MetroText(label, MetroType.body)
            MetroText(description ?: if (checked) "On" else "Off", MetroType.caption, color = colors.subtle)
        }
        val thumbX by animateDpAsState(if (checked) 28.dp else 6.dp, tween(120), label = "toggleThumb")
        Box(
            Modifier
                .size(width = 48.dp, height = 24.dp)
                .then(
                    if (checked) {
                        Modifier.background(colors.accent, RoundedCornerShape(50))
                    } else {
                        Modifier.drawBehind {
                            val stroke = 2.dp.toPx()
                            drawRoundRect(
                                colors.foreground,
                                topLeft = Offset(stroke / 2, stroke / 2),
                                size = Size(size.width - stroke, size.height - stroke),
                                cornerRadius = CornerRadius(size.height / 2),
                                style = Stroke(stroke),
                            )
                        }
                    },
                ),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbX)
                    .size(if (checked) 14.dp else 12.dp)
                    .background(if (checked) colors.onSolidAccent else colors.foreground, CircleShape),
            )
        }
    }
}

/** Windows 10 Mobile button: a flat grey rectangle with lowercase text that tilts when pressed. */
@Composable
fun MetroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .metroTilt()
            .defaultMinSize(minHeight = 44.dp)
            .background(Metro.colors.control)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        MetroText(text, MetroType.button, maxLines = 2)
    }
}

/**
 * Slider with a thin track, an accent fill and a square thumb. [value] is 0..1;
 * [onValueChangeFinished] fires when the finger lifts, so callers can save then.
 *
 * For TalkBack, [steps] is the number of stops between the ends (0 is continuous), so a swipe
 * moves one stop instead of an arbitrary amount, and [valueDescription] words the current value
 * ("24 dp") instead of a bare percentage. A TalkBack change has no finger to lift, so it counts
 * as finished at once.
 */
@Composable
fun MetroSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    valueDescription: ((Float) -> String)? = null,
    onValueChangeFinished: () -> Unit = {},
) {
    val colors = Metro.colors
    val fraction = value.coerceIn(0f, 1f)
    var width by remember { mutableIntStateOf(0) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    val thumbWidth = 12.dp
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .onSizeChanged { width = it.width }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps)
                valueDescription?.let { stateDescription = it(fraction) }
                setProgress {
                    val stops = (steps + 1).takeIf { steps > 0 }
                    val target = it.coerceIn(0f, 1f)
                    change(if (stops == null) target else (target * stops).roundToInt() / stops.toFloat())
                    finished()
                    true
                }
            }
            .pointerInput(Unit) {
                val thumb = thumbWidth.toPx()
                fun at(x: Float) = ((x - thumb / 2) / (size.width - thumb)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(at(down.position.x))
                    awaitHorizontalTouchSlopOrCancellation(down.id) { move, _ -> move.consume() }?.let { slop ->
                        change(at(slop.position.x))
                        horizontalDrag(slop.id) { move ->
                            change(at(move.position.x))
                            move.consume()
                        }
                    }
                    finished()
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(colors.control))
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(colors.accent))
        Box(
            Modifier
                .offset { IntOffset(((width - thumbWidth.roundToPx()) * fraction).toInt(), 0) }
                .size(thumbWidth, 26.dp)
                .background(colors.foreground),
        )
    }
}

/**
 * A settings slider with its label and the current value above it. The value is an integer in
 * [range] shown through [format]; the slider follows the finger and [onCommit] runs once it lets
 * go (or, for TalkBack, after every change), so the setting is saved once, not on every move.
 */
@Composable
fun LabeledSlider(
    label: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String,
    modifier: Modifier = Modifier,
    onCommit: suspend (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val span = (range.last - range.first).coerceAtLeast(1)
    var dragged by remember { mutableStateOf<Float?>(null) }
    fun valueAt(fraction: Float) = range.first + (fraction * span).roundToInt()
    val fraction = dragged ?: ((value - range.first).toFloat() / span).coerceIn(0f, 1f)
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetroText(label, MetroType.body, modifier = Modifier.weight(1f).padding(end = 12.dp))
            MetroText(format(valueAt(fraction)), MetroType.body, color = Metro.colors.accent)
        }
        MetroSlider(
            value = fraction,
            onValueChange = { dragged = it },
            modifier = Modifier.padding(top = 2.dp),
            // About 5% per TalkBack swipe on wide ranges; one unit on narrow ones.
            steps = (span - 1).coerceAtMost(19),
            valueDescription = { format(valueAt(it)) },
            // Read the drag when it ends, not from this composition: a TalkBack change finishes
            // before the page has recomposed.
            onValueChangeFinished = {
                dragged?.let { f ->
                    scope.launch {
                        onCommit(valueAt(f))
                        dragged = null
                    }
                }
            },
        )
    }
}

/** A selectable choice (theme, icon pack, start background): accent-filled when [selected]. */
@Composable
fun MetroChoice(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Metro.colors
    Box(
        modifier
            .metroTilt()
            .background(if (selected) colors.accent else colors.control)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        MetroText(label, MetroType.button, color = if (selected) colors.onSolidAccent else colors.foreground, maxLines = 2)
    }
}

/**
 * A Windows 10 Mobile flyout: the page dims and [content] slides up a little as it fades in.
 * Tapping the dimmed page or pressing back calls [onDismiss]. Entry is animated, exit is immediate.
 *
 * It is a pane for TalkBack, announced as [paneTitle] and read as one group of its own.
 */
@Composable
fun MetroFlyout(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    paneTitle: String = "menu",
    content: @Composable BoxScope.() -> Unit,
) {
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }
    BackHandler(onBack = onDismiss)
    Box(
        modifier.fillMaxSize().semantics {
            this.paneTitle = paneTitle
            isTraversalGroup = true
        },
    ) {
        AnimatedVisibility(entered, Modifier.fillMaxSize(), enter = fadeIn(tween(160)), exit = ExitTransition.None) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Metro.colors.scrim)
                    .clickable(
                        remember { MutableInteractionSource() },
                        null,
                        onClickLabel = "dismiss",
                        role = Role.Button,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "dismiss" },
            )
        }
        AnimatedVisibility(
            entered,
            Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)) + slideInVertically(tween(220, easing = FastOutSlowInEasing)) { it / 16 },
            exit = ExitTransition.None,
        ) {
            Box(Modifier.fillMaxSize()) { content() }
        }
    }
}

/** A Windows 10 Mobile context menu: full-width chrome panel with lowercase actions. */
@Composable
fun MetroMenu(items: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Metro.colors.chrome)
            .padding(vertical = 8.dp),
    ) {
        items.forEach { (label, action) ->
            MetroText(
                label,
                MetroType.menuItem,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = action)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}
