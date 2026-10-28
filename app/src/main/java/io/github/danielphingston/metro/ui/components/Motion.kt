package io.github.danielphingston.metro.ui.components

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.danielphingston.metro.data.LaunchStyle
import io.github.danielphingston.metro.data.LauncherSettings
import kotlin.math.roundToInt

/**
 * Every motion setting the UI reads while it draws, in one small immutable value: [MetroTheme]
 * provides it as [LocalMotion] from the settings, so the tilt, the flips and the launch
 * animation need no plumbing through the screens.
 */
@Immutable
data class Motion(
    val launchStyle: LaunchStyle = LaunchStyle.TURNSTILE,
    /** 1 is as designed, 2 is twice as fast. */
    val speed: Float = 1f,
    val animateReturn: Boolean = true,
    val openDuringAnimation: Boolean = true,
    val tiltEnabled: Boolean = true,
    val tiltDegrees: Float = LauncherSettings.DEFAULT_TILT_DEGREES.toFloat(),
    val flipsEnabled: Boolean = true,
    val flipIntervalMs: Long = LauncherSettings.DEFAULT_FLIP_INTERVAL * 1_000L,
    val swingPages: Boolean = true,
) {
    /** [ms] of designed animation time, at this speed. */
    fun duration(ms: Int): Int = (ms / speed).roundToInt().coerceAtLeast(1)

    companion object {
        fun of(settings: LauncherSettings) = Motion(
            launchStyle = settings.launchStyle,
            speed = LauncherSettings.clampAnimationSpeed(settings.animationSpeed) / 100f,
            animateReturn = settings.animateReturn,
            openDuringAnimation = settings.openDuringAnimation,
            tiltEnabled = settings.tiltEnabled,
            tiltDegrees = LauncherSettings.clampTiltDegrees(settings.tiltDegrees).toFloat(),
            flipsEnabled = settings.liveTileFlips,
            flipIntervalMs = LauncherSettings.clampFlipInterval(settings.flipIntervalSeconds) * 1_000L,
            swingPages = settings.swingPages,
        )
    }
}

/** The motion settings; the defaults apply wherever no theme provides them (previews, tests). */
val LocalMotion = staticCompositionLocalOf { Motion() }
