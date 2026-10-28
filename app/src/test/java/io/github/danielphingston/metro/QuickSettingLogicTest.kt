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
        assertEquals("off", QuickSettingLogic.networkText(enabled = false, connected = true))
        assertEquals("connected", QuickSettingLogic.networkText(enabled = true, connected = true))
        assertEquals("on", QuickSettingLogic.networkText(enabled = true, connected = false))
        assertEquals("on", QuickSettingLogic.networkText(enabled = true, connected = null))
        assertEquals("vibrate", QuickSettingLogic.ringerText(AudioManager.RINGER_MODE_VIBRATE))
    }

    @Test
    fun onlyPanelsOpenOverStartAndBatteryAndHotspotLaunchPlain() {
        val styles = QuickSettingKind.entries.associateWith(QuickSettingLogic::launchStyle)
        assertEquals(
            setOf(QuickSettingKind.WIFI, QuickSettingKind.MOBILE_DATA),
            styles.filterValues { it == QuickSettingLogic.LaunchStyle.OVER_START }.keys,
        )
        assertEquals(QuickSettingLogic.LaunchStyle.NO_ANIMATION, styles[QuickSettingKind.BATTERY])
        assertEquals(QuickSettingLogic.LaunchStyle.NO_ANIMATION, styles[QuickSettingKind.HOTSPOT])
        assertEquals(QuickSettingLogic.LaunchStyle.TURNSTILE, styles[QuickSettingKind.BLUETOOTH])
        assertEquals(QuickSettingLogic.LaunchStyle.TURNSTILE, styles[QuickSettingKind.DO_NOT_DISTURB])
    }

    private fun wifi(info: QuickInfo.Wifi, on: Boolean = true) =
        QuickState(on, QuickSettingLogic.networkText(on, info.connected), info = info)

    @Test
    fun wifiBandFollowsTheFrequency() {
        assertEquals("2.4 ghz", QuickSettingLogic.wifiBand(2437))
        assertEquals("5 ghz", QuickSettingLogic.wifiBand(5180))
        assertEquals("6 ghz", QuickSettingLogic.wifiBand(5955))
        assertEquals("6 ghz", QuickSettingLogic.wifiBand(7115))
        assertNull(QuickSettingLogic.wifiBand(0))
        assertNull(QuickSettingLogic.wifiBand(900))
    }

    @Test
    fun androidsPlaceholderNetworkNameIsUnknown() {
        assertEquals("home", QuickSettingLogic.cleanSsid("\"home\""))
        assertEquals("home", QuickSettingLogic.cleanSsid("home"))
        assertNull(QuickSettingLogic.cleanSsid("<unknown ssid>"))
        assertNull(QuickSettingLogic.cleanSsid("\"<unknown ssid>\""))
        assertNull(QuickSettingLogic.cleanSsid("\"\""))
        assertNull(QuickSettingLogic.cleanSsid(null))
    }

    @Test
    fun signalBarsGrowWithTheLevel() {
        assertEquals("▂▄▆", QuickSettingLogic.signalBars(3))
        assertEquals("▂▄▆█", QuickSettingLogic.signalBars(4))
        assertNull(QuickSettingLogic.signalBars(-1))
        assertEquals(4, QuickSettingLogic.bars(4, 4))
        assertEquals(2, QuickSettingLogic.bars(2, 4))
        assertEquals(-1, QuickSettingLogic.bars(-1, 4))
    }

    @Test
    fun wifiLinesAddDetailsAsRoomGrows() {
        val state = wifi(QuickInfo.Wifi(true, 3, 5180, 866, null))
        assertEquals(listOf("connected · ▂▄▆"), QuickSettingLogic.lines(state, 1))
        assertEquals(listOf("connected · ▂▄▆", "5 ghz · 866 mbps"), QuickSettingLogic.lines(state, 2))
        assertEquals(listOf("connected", "▂▄▆ · 5 ghz", "866 mbps"), QuickSettingLogic.lines(state, 3))
    }

    @Test
    fun wifiNameReplacesConnectedWhenKnown() {
        val state = wifi(QuickInfo.Wifi(true, 4, 2412, -1, "home"))
        assertEquals(listOf("home · ▂▄▆█", "2.4 ghz"), QuickSettingLogic.lines(state, 2))
    }

    @Test
    fun wifiThatIsOffOrNotConnectedStaysOneLine() {
        assertEquals(listOf("off"), QuickSettingLogic.lines(wifi(QuickInfo.Wifi(false, -1, 0, -1, null), on = false), 3))
        assertEquals(listOf("on"), QuickSettingLogic.lines(wifi(QuickInfo.Wifi(false, -1, 0, -1, null)), 3))
    }

    @Test
    fun mobileLinesShowCarrierSignalAndRoaming() {
        val state = QuickState(true, "connected", info = QuickInfo.Mobile(true, "telco", 2, true))
        assertEquals(listOf("telco · ▂▄"), QuickSettingLogic.lines(state, 1))
        assertEquals(listOf("telco · ▂▄", "connected · roaming"), QuickSettingLogic.lines(state, 2))
        val noSim = QuickState(true, "on", info = QuickInfo.Mobile(false, "", -1, false))
        assertEquals(listOf("on"), QuickSettingLogic.lines(noSim, 2))
        val off = QuickState(false, "off", info = QuickInfo.Mobile(false, "telco", 3, false))
        assertEquals(listOf("off"), QuickSettingLogic.lines(off, 1))
    }

    @Test
    fun bluetoothLinesListDevicesAndCountTheRest() {
        fun state(vararg names: String) = QuickState(true, "on", info = QuickInfo.Bluetooth(names.toList()))
        assertEquals(listOf("on"), QuickSettingLogic.lines(state(), 2))
        assertEquals(listOf("buds"), QuickSettingLogic.lines(state("buds"), 1))
        assertEquals(listOf("buds +1"), QuickSettingLogic.lines(state("buds", "car"), 1))
        assertEquals(listOf("buds", "car"), QuickSettingLogic.lines(state("buds", "car"), 2))
        assertEquals(listOf("buds", "+2 more"), QuickSettingLogic.lines(state("buds", "car", "watch"), 2))
        val off = QuickState(false, "off", info = QuickInfo.Bluetooth(listOf("buds")))
        assertEquals(listOf("off"), QuickSettingLogic.lines(off, 3))
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
    fun dataEnabledPrefersTheSubscriptionAnswerOverTheGlobalSetting() {
        assertEquals(false, QuickSettingLogic.dataEnabled(perSubscription = false, global = true))
        assertEquals(true, QuickSettingLogic.dataEnabled(perSubscription = true, global = false))
        assertEquals(true, QuickSettingLogic.dataEnabled(perSubscription = null, global = true))
        assertEquals(false, QuickSettingLogic.dataEnabled(perSubscription = null, global = false))
    }

    @Test
    fun onlyPlainOnOffSettingsAreToggles() {
        assertEquals(true, QuickSettingLogic.isToggle(QuickSettingKind.WIFI))
        assertEquals(false, QuickSettingLogic.isToggle(QuickSettingKind.BRIGHTNESS))
        assertEquals(false, QuickSettingLogic.isToggle(QuickSettingKind.BATTERY))
    }

    @Test
    fun aKindWithoutExtrasReadsAsItsStateLine() {
        assertEquals(listOf("on"), QuickSettingLogic.lines(QuickState(true, "on"), 3))
        assertEquals(emptyList<String>(), QuickSettingLogic.lines(QuickState(true, ""), 3))
    }
}
