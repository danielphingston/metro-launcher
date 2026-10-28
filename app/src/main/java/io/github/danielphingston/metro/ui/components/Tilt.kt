package io.github.danielphingston.metro.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The numbers behind the tilt, as pure functions. */
internal object Tilt {
    /** The strength callers' `maxDegrees` are written against; the user's setting scales them relative to it. */
    const val BASE_DEGREES = 9f

    fun degrees(maxDegrees: Float, setting: Float) = maxDegrees * setting / BASE_DEGREES

    /** nx and ny are the press position across the element, -1 at one edge to 1 at the other. */
    fun rotationY(nx: Float, degrees: Float) = nx * degrees
    fun rotationX(ny: Float, degrees: Float) = -ny * degrees

    /** Pressed in the middle the element sinks the most; at the edge it only tips. */
    fun scale(nx: Float, ny: Float): Float = 1f - 0.04f * (1f - maxOf(abs(nx), abs(ny)).coerceAtMost(1f))
}

/**
 * The Windows Phone "tilt" press effect: the element tips toward the finger, or sinks when
 * pressed in the middle. Observes pointer events without consuming them, so it composes with
 * any click or drag handling on the same element. [maxDegrees] is relative to the default
 * strength; the user's tilt setting ([LocalMotion]) scales it, and can turn the effect off.
 *
 * Turning [enabled] off only stops the effect (and eases back to rest); the modifier chain keeps
 * its shape. Dropping the modifier instead would rebuild the element's pointer input chain, and
 * that cancels a gesture already running on it: a tile that goes into edit mode while pressed
 * would lose the drag that follows.
 */
fun Modifier.metroTilt(enabled: Boolean = true, maxDegrees: Float = Tilt.BASE_DEGREES): Modifier =
    this then TiltElement(enabled, maxDegrees)

private class TiltElement(val enabled: Boolean, val maxDegrees: Float) : ModifierNodeElement<TiltNode>() {
    override fun create() = TiltNode(enabled, maxDegrees)

    override fun update(node: TiltNode) {
        node.maxDegrees = maxDegrees
        node.updateEnabled(enabled)
    }

    override fun equals(other: Any?) =
        other is TiltElement && other.enabled == enabled && other.maxDegrees == maxDegrees

    override fun hashCode() = 31 * enabled.hashCode() + maxDegrees.hashCode()
}

/**
 * The pose is a blend from where the last animation started ([fromX], [fromY], [fromScale]) to
 * where it is going, by [fraction]; a new target starts from the current pose, so presses and
 * releases in quick succession never jump. Only [fraction] is observable state, and it is read
 * in the layer block alone: a tilt redraws the element and never recomposes or re-measures it.
 */
private class TiltNode(enabled: Boolean, var maxDegrees: Float) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    private var enabled = enabled
    private val fraction = mutableFloatStateOf(1f)
    private var fromX = 0f
    private var fromY = 0f
    private var fromScale = 1f
    private var toX = 0f
    private var toY = 0f
    private var toScale = 1f
    private var job: Job? = null

    val rotationX get() = fromX + (toX - fromX) * fraction.floatValue
    val rotationY get() = fromY + (toY - fromY) * fraction.floatValue
    val scale get() = fromScale + (toScale - fromScale) * fraction.floatValue

    // Delegated layout first, pointer input second: the pointer node then sits outside the layer
    // and sees the element's untilted bounds.
    init {
        delegate(TiltLayerNode(this))
        delegate(SuspendingPointerInputModifierNode { track() })
    }

    fun updateEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) animateTo(0f, 0f, 1f, 150)
    }

    private fun animateTo(x: Float, y: Float, s: Float, millis: Int) {
        fromX = rotationX
        fromY = rotationY
        fromScale = scale
        toX = x
        toY = y
        toScale = s
        job?.cancel()
        fraction.floatValue = 0f
        job = coroutineScope.launch {
            animate(0f, 1f, animationSpec = tween(millis)) { value, _ -> fraction.floatValue = value }
        }
    }

    private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.track() {
        awaitPointerEventScope {
            var down: Offset? = null
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull() ?: continue
                val start = down
                if (change.pressed && !change.previousPressed) {
                    down = change.position
                    val motion = currentValueOf(LocalMotion)
                    if (enabled && motion.tiltEnabled) {
                        val nx = (change.position.x / size.width - 0.5f) * 2
                        val ny = (change.position.y / size.height - 0.5f) * 2
                        val degrees = Tilt.degrees(maxDegrees, motion.tiltDegrees)
                        animateTo(Tilt.rotationX(ny, degrees), Tilt.rotationY(nx, degrees), Tilt.scale(nx, ny), 90)
                    }
                } else if (start != null &&
                    (!change.pressed || (change.position - start).getDistance() > viewConfiguration.touchSlop)
                ) {
                    // Released, or the finger started scrolling/dragging: spring back.
                    down = null
                    animateTo(0f, 0f, 1f, 150)
                }
            }
        }
    }
}

private class TiltLayerNode(private val tilt: TiltNode) : Modifier.Node(), LayoutModifierNode {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                rotationX = tilt.rotationX
                rotationY = tilt.rotationY
                scaleX = tilt.scale
                scaleY = tilt.scale
                cameraDistance = 14f * density
            }
        }
    }
}
