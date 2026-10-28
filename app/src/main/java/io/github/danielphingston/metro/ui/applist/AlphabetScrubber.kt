package io.github.danielphingston.metro.ui.applist

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ScrubberHighlight
import io.github.danielphingston.metro.data.ScrubberHighlightPosition
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlin.math.roundToInt

/** How much room the strip takes from the list's end edge. */
internal val ScrubberWidth = 28.dp

private val SlotMax = 17.dp
private val WaveReach = 56.dp

private val BubbleSize = 128.dp
private val BubbleRectWidth = 144.dp
private val BubbleRectHeight = 120.dp

/** How far the rounded rectangle's soft edge reaches in from its outline. */
private val Feather = 20.dp
private const val FeatherLayers = 10

/**
 * A strip of the list's letters along the end edge. Touch or drag on it to jump the list to that
 * letter; the letters near the finger bulge toward the screen centre and a big preview shows the letter,
 * on the [highlight] backdrop. The strip hugs the bottom of its space so a thumb reaches all of it.
 * It takes the whole gesture from the first touch, so the pager and the list don't scroll with it.
 */
@Composable
internal fun AlphabetScrubber(
    letters: List<String>,
    onScrub: (String) -> Unit,
    modifier: Modifier = Modifier,
    highlight: ScrubberHighlight = ScrubberHighlight.CIRCLE,
    highlightPosition: ScrubberHighlightPosition = ScrubberHighlightPosition.FIXED,
    positionPercent: Int = LauncherSettings.DEFAULT_SCRUBBER_POSITION,
) {
    if (letters.isEmpty()) return
    val colors = Metro.colors
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // Toward the screen centre is left in LTR, right in RTL.
    val inward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1f else -1f
    var selected by remember { mutableIntStateOf(-1) }
    var fingerPos by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    var touching by remember { mutableStateOf(false) }
    var jumped by remember { mutableStateOf("") }
    val active = animateFloatAsState(
        if (touching) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "scrubActive",
    )
    val bubbleAlpha = animateFloatAsState(if (touching) 1f else 0f, tween(160), label = "scrubBubble")
    // The letter stays up while the bubble fades out after the finger lifts.
    var previewIndex by remember { mutableIntStateOf(0) }
    if (selected >= 0) previewIndex = selected
    val reach = with(density) { WaveReach.toPx() }
    val bubbleHeight = with(density) { (if (highlight == ScrubberHighlight.ROUNDED_RECT) BubbleRectHeight else BubbleSize).roundToPx() }
    val bubbleWidth = with(density) { (if (highlight == ScrubberHighlight.ROUNDED_RECT) BubbleRectWidth else BubbleSize).roundToPx() }
    val count = letters.size

    BoxWithConstraints(modifier.width(ScrubberWidth).fillMaxHeight()) {
        val slot = with(density) {
            AlphabetScrub.slotHeight(maxHeight.toPx(), count, SlotMax.toPx()).toDp()
        }
        val columnHeight = slot * count
        val columnTop = with(density) {
            AlphabetScrub.stripTop(
                maxHeight.toPx(), columnHeight.toPx(), AlphabetScrub.bottomGap(maxHeight.toPx(), positionPercent),
            ).toDp()
        }
        val top = with(density) { columnTop.toPx() }
        val description = "alphabet scrubber"
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(y = columnTop)
                .width(ScrubberWidth)
                .height(columnHeight)
                .clearAndSetSemantics {
                    contentDescription = description
                    // The action jumps the list without the finger-driven preview, so say where it went:
                    // a polite live region announces each change of this state.
                    liveRegion = LiveRegionMode.Polite
                    if (jumped.isNotEmpty()) stateDescription = jumped
                    customActions = letters.map { letter ->
                        CustomAccessibilityAction("jump to $letter") {
                            onScrub(letter)
                            jumped = "jumped to ${letter.uppercase()}"
                            true
                        }
                    }
                }
                .pointerInput(letters) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        touching = true
                        var change = down
                        while (true) {
                            val height = size.height.toFloat()
                            val index = AlphabetScrub.indexAt(change.position.y, height, count)
                            fingerPos = AlphabetScrub.position(change.position.y, height, count)
                            fingerY = change.position.y.coerceIn(0f, height)
                            if (index != selected) {
                                selected = index
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onScrub(letters[index])
                            }
                            change.consume()
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                        }
                        touching = false
                        selected = -1
                    }
                },
        ) {
            letters.forEachIndexed { i, letter ->
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(y = slot * i)
                        .size(ScrubberWidth, slot)
                        .graphicsLayer {
                            val d = i - fingerPos
                            val a = active.value
                            translationX = inward * AlphabetScrub.offset(d, reach) * a
                            val s = 1f + (AlphabetScrub.scale(d) - 1f) * a
                            scaleX = s
                            scaleY = s
                            alpha = 0.55f + 0.45f * AlphabetScrub.falloff(d) * a
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MetroText(
                        letter.uppercase(),
                        MetroType.style(11.sp, FontWeight.SemiBold),
                        color = if (i == selected) colors.accent else colors.subtle,
                    )
                }
            }
        }
        // The big preview: at a fixed spot at eye level, or left of the finger and the bulge.
        if (bubbleAlpha.value > 0f && previewIndex in letters.indices) {
            val accent = colors.accent
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val x = inward * (reach + bubbleWidth + 16.dp.toPx()) * 0.9f
                        val centreY = if (highlightPosition == ScrubberHighlightPosition.FIXED) {
                            AlphabetScrub.fixedCentre(maxHeight.toPx())
                        } else {
                            top + fingerY
                        }
                        IntOffset(x.roundToInt(), centreY.roundToInt() - bubbleHeight / 2)
                    }
                    // The strip is only ScrubberWidth wide: let the bubble take its own size regardless.
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(with(density) { bubbleWidth.toDp() }, with(density) { bubbleHeight.toDp() })
                    .graphicsLayer { alpha = bubbleAlpha.value.coerceIn(0f, 1f) }
                    .drawBehind {
                        when (highlight) {
                            ScrubberHighlight.NONE -> Unit
                            // Solid through the middle, then fading to nothing at the rim.
                            ScrubberHighlight.CIRCLE -> drawCircle(
                                Brush.radialGradient(
                                    0f to accent, 0.5f to accent, 0.78f to accent.copy(alpha = 0.35f), 1f to Color.Transparent,
                                    center = center, radius = size.minDimension / 2,
                                ),
                                radius = size.minDimension / 2,
                            )
                            // Rounded rectangles shrinking inwards, each a little more opaque: a feathered edge.
                            ScrubberHighlight.ROUNDED_RECT -> {
                                val step = Feather.toPx() / FeatherLayers
                                for (layer in 0 until FeatherLayers) {
                                    val inset = step * layer
                                    val radius = (size.height / 2 - inset).coerceAtLeast(0f) * 0.7f
                                    drawRoundRect(
                                        accent.copy(alpha = AlphabetScrub.featherAlpha(layer, FeatherLayers)),
                                        topLeft = Offset(inset, inset),
                                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                                        cornerRadius = CornerRadius(radius),
                                    )
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Never truncated or wrapped: the box is generous and wide glyphs may spill past it.
                BasicText(
                    letters[previewIndex].uppercase(),
                    style = MetroType.style(64.sp, FontWeight.Light)
                        .copy(color = if (highlight == ScrubberHighlight.NONE) accent else colors.onSolidAccent),
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}
