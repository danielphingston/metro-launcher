package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.LaunchStyle
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.ui.components.LabeledSlider
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.components.Motion
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** The animations page: how apps open, how fast things move, and the smaller effects. */
@Composable
internal fun AnimationsPage(settings: LauncherSettings, repository: SettingsRepository) {
    val scope = rememberCoroutineScope()
    val colors = Metro.colors

    Section("launch animation")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LaunchStyle.entries.chunked(2).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                line.forEach { style ->
                    MetroChoice(
                        style.label,
                        settings.launchStyle == style,
                        { scope.launch { repository.setLaunchStyle(style) } },
                        Modifier.weight(1f),
                    )
                }
                // An odd last choice keeps the width of the others.
                if (line.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    MetroText(settings.launchStyle.description, MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(12.dp))
    LabeledSlider(
        "Speed",
        settings.animationSpeed,
        LauncherSettings.MIN_ANIMATION_SPEED..LauncherSettings.MAX_ANIMATION_SPEED,
        Motion::speedLabel,
    ) { repository.setAnimationSpeed(it) }
    MetroText(
        "Also follows the animation scale in Android's developer options.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(8.dp))
    MetroToggle(
        "Animate return to launcher",
        settings.animateReturn,
        { scope.launch { repository.setAnimateReturn(it) } },
        description = "Play the launch animation backwards when you come back",
    )
    MetroToggle(
        "Open apps during the animation",
        settings.openDuringAnimation,
        { scope.launch { repository.setOpenDuringAnimation(it) } },
        description = "Start the app while the launcher is still animating, so it opens sooner",
    )

    Section("tilt")
    MetroToggle(
        "Tilt when pressed",
        settings.tiltEnabled,
        { scope.launch { repository.setTiltEnabled(it) } },
        description = "Tiles and list items tip toward your finger",
    )
    if (settings.tiltEnabled) {
        LabeledSlider(
            "Tilt strength",
            settings.tiltDegrees,
            LauncherSettings.MIN_TILT_DEGREES..LauncherSettings.MAX_TILT_DEGREES,
            { "$it°" },
        ) { repository.setTiltDegrees(it) }
    }

    Section("live tiles")
    MetroToggle(
        "Live tile flips",
        settings.liveTileFlips,
        { scope.launch { repository.setLiveTileFlips(it) } },
        description = "Live tiles turn over to show their other side",
    )
    if (settings.liveTileFlips) {
        LabeledSlider(
            "Flip every",
            settings.flipIntervalSeconds,
            LauncherSettings.MIN_FLIP_INTERVAL..LauncherSettings.MAX_FLIP_INTERVAL,
            { "$it s" },
        ) { repository.setFlipInterval(it) }
    }

    Section("pages")
    MetroToggle(
        "Swing pages",
        settings.swingPages,
        { scope.launch { repository.setSwingPages(it) } },
        description = "Start and the app list lean as you swipe between them",
    )
}
