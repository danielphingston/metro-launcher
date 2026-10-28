package io.github.danielphingston.metro

import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * Shared by every screenshot test. A small change threshold absorbs anti-aliasing noise between
 * machines while still failing the comparison on real layout or colour changes.
 */
val ScreenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f),
)
