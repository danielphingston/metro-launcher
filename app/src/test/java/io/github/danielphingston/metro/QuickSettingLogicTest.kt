package io.github.danielphingston.metro

import android.media.AudioManager
import io.github.danielphingston.metro.data.QuickSettingKind
import io.github.danielphingston.metro.live.QuickInfo
import io.github.danielphingston.metro.live.QuickSettingLogic
import io.github.danielphingston.metro.live.QuickState
import io.github.danielphingston.metro.live.QuickSettingLogic.Brightness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickSettingLogicTest {
    @Test
    fun brightnessStepsCycleThroughAutoAndThreeLevels() {
        val steps = generateSequence(Brightness.AUTO) { it.next() }.take(5).toList()
        assertEquals(
            listOf(Brightness.AUTO, Brightness.QUARTER, Brightness.HALF, Brightness.FULL, Brightness.AUTO),
            steps,
        )
    }

    @Test
    fun aStoredBrightnessSnapsToTheNearestStep() {
        assertEquals(Brightness.AUTO, Brightness.of(auto = true, value = 255))
        assertEquals(Brightness.QUARTER, Brightness.of(auto = false, value = 0))
        assertEquals(Brightness.QUARTER, Brightness.of(auto = false, value = 64))
        assertEquals(Brightness.HALF, Brightness.of(auto = false, value = 100))
        assertEquals(Brightness.HALF, Brightness.of(auto = false, value = 128))
        assertEquals(Brightness.FULL, Brightness.of(auto = false, value = 200))
        assertEquals(Brightness.FULL, Brightness.of(auto = false, value = 255))
    }

    @Test
    fun everyManualStepReadsBackAsItself() {
        Brightness.entries.filter { it.value != null }.forEach {
            assertEquals(it, Brightness.of(auto = false, value = it.value!!))
        }
    }

    @Test
    fun theRingerCyclesRingVibrateSilent() {
        val normal = AudioManager.RINGER_MODE_NORMAL
        val vibrate = AudioManager.RINGER_MODE_VIBRATE
        val silent = AudioManager.RINGER_MODE_SILENT
        assertEquals(vibrate, QuickSettingLogic.nextRinger(normal))
        assertEquals(silent, QuickSettingLogic.nextRinger(vibrate))
        assertEquals(normal, QuickSettingLogic.nextRinger(silent))
        assertEquals(normal, QuickSettingLogic.nextRinger(-1))
    }

    @Test
    fun batteryPercentRoundsAndRejectsBadBroadcasts() {
        assertEquals(78, QuickSettingLogic.batteryPercent(78, 100))
        assertEquals(50, QuickSettingLogic.batteryPercent(127, 255))
        assertEquals(100, QuickSettingLogic.batteryPercent(300, 100))
        assertEquals(-1, QuickSettingLogic.batteryPercent(-1, 100))
        assertEquals(-1, QuickSettingLogic.batteryPercent(50, 0))
    }

    @Test
    fun stateTexts() {
        assertEquals("78%", QuickSettingLogic.batteryText(78, charging = false))
        assertEquals("78% · charging", QuickSettingLogic.batteryText(78, charging = true))
        assertEquals("", QuickSettingLogic.batteryText(-1, charging = true))
        assertEquals("vibrate", QuickSettingLogic.ringerText(AudioManager.RINGER_MODE_VIBRATE))
    }

    @Test
    fun onlyBatteryLaunchesPlain() {
        val styles = QuickSettingKind.entries.associateWith(QuickSettingLogic::launchStyle)
        assertEquals(setOf(QuickSettingKind.BATTERY), styles.filterValues { it == QuickSettingLogic.LaunchStyle.NO_ANIMATION }.keys)
        assertEquals(QuickSettingLogic.LaunchStyle.TURNSTILE, styles[QuickSettingKind.DO_NOT_DISTURB])
    }

    @Test
    fun batteryLinesShowStatusTimeToFullAndTemperature() {
        val charging = QuickState(true, "80% · charging", 80, QuickInfo.Battery(80, true, 4_800_000, 31.5f))
        assertEquals(listOf("80% · charging"), QuickSettingLogic.lines(charging, 1))
        assertEquals(listOf("80% · charging", "1 h 20 min to full · 31.5°c"), QuickSettingLogic.lines(charging, 2))
        assertEquals(listOf("80% · charging", "1 h 20 min to full", "31.5°c"), QuickSettingLogic.lines(charging, 3))
        val draining = QuickState(true, "80%", 80, QuickInfo.Battery(80, false, -1, null))
        assertEquals(listOf("80%"), QuickSettingLogic.lines(draining, 1))
        assertEquals(listOf("80% · discharging"), QuickSettingLogic.lines(draining, 3))
    }

    @Test
    fun chargeTimeIsRoundedUpAndUnknownWhenNotPositive() {
        assertEquals("45 min to full", QuickSettingLogic.chargeTimeText(45 * 60_000L))
        assertEquals("1 min to full", QuickSettingLogic.chargeTimeText(1_000))
        assertEquals("2 h to full", QuickSettingLogic.chargeTimeText(2 * 3_600_000L))
        assertNull(QuickSettingLogic.chargeTimeText(-1))
        assertNull(QuickSettingLogic.chargeTimeText(0))
    }

    @Test
    fun batteryTemperatureComesInTenthsOfADegree() {
        assertEquals(31.5f, QuickSettingLogic.batteryTempC(315)!!, 0.001f)
        assertEquals(0f, QuickSettingLogic.batteryTempC(0)!!, 0.001f)
        assertNull(QuickSettingLogic.batteryTempC(null))
        assertNull(QuickSettingLogic.batteryTempC(9999))
        assertEquals("31.5°c", QuickSettingLogic.temperatureText(31.5f))
    }

    @Test
    fun onlyPlainOnOffSettingsAreToggles() {
        assertEquals(true, QuickSettingLogic.isToggle(QuickSettingKind.FLASHLIGHT))
        assertEquals(false, QuickSettingLogic.isToggle(QuickSettingKind.BRIGHTNESS))
        assertEquals(false, QuickSettingLogic.isToggle(QuickSettingKind.BATTERY))
    }

    @Test
    fun aKindWithoutExtrasReadsAsItsStateLine() {
        assertEquals(listOf("on"), QuickSettingLogic.lines(QuickState(true, "on"), 3))
        assertEquals(emptyList<String>(), QuickSettingLogic.lines(QuickState(true, ""), 3))
    }
}
