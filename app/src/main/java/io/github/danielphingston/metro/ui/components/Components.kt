package io.github.danielphingston.metro.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
 */
fun Modifier.metroTilt(enabled: Boolean = true, maxDegrees: Float = 9f): Modifier = composed {
    if (!enabled) return@composed this
    val rotX = remember { Animatable(0f) }
    val rotY = remember { Animatable(0f) }
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
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
                        animate(nx * maxDegrees, -ny * maxDegrees, 1f - 0.04f * (1f - edge), 90)
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

/** Windows Phone toggle switch: a bordered track with a solid thumb. */
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
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetroText(label, MetroType.body)
            MetroText(description ?: if (checked) "On" else "Off", MetroType.caption, color = colors.subtle)
        }
        Box(
            Modifier
                .size(width = 64.dp, height = 28.dp)
                .border(2.dp, colors.foreground)
                .padding(4.dp),
        ) {
            if (checked) Box(Modifier.size(width = 56.dp, height = 20.dp).background(colors.accent))
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = if (checked) 42.dp else (-2).dp)
                    .size(width = 14.dp, height = 28.dp)
                    .background(colors.foreground),
            )
        }
    }
}

/** Windows Phone button: a rectangle with a 2dp border, lowercase-friendly text. */
@Composable
fun MetroButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .metroTilt()
            .border(2.dp, Metro.colors.foreground)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        MetroText(text, MetroType.body.copy(fontSize = MetroType.body.fontSize * 0.9f))
    }
}

/** A Windows Phone context menu: full-width chrome panel with lowercase actions. */
@Composable
fun MetroMenu(items: List<Pair<String, () -> Unit>>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Metro.colors.chrome)
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { (label, action) ->
            MetroText(
                label,
                MetroType.body.copy(fontSize = MetroType.body.fontSize * 1.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = action)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}
