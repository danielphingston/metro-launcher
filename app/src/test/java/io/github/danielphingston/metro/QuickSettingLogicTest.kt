package io.github.danielphingston.metro

import android.media.AudioManager
import io.github.danielphingston.metro.live.QuickSettingLogic
import io.github.danielphingston.metro.live.QuickSettingLogic.Brightness
import org.junit.Assert.assertEquals
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
        assertEquals("off", QuickSettingLogic.networkText(enabled = false, connected = true))
        assertEquals("connected", QuickSettingLogic.networkText(enabled = true, connected = true))
        assertEquals("on", QuickSettingLogic.networkText(enabled = true, connected = false))
        assertEquals("on", QuickSettingLogic.networkText(enabled = true, connected = null))
        assertEquals("vibrate", QuickSettingLogic.ringerText(AudioManager.RINGER_MODE_VIBRATE))
    }
}
