package io.github.danielphingston.metro

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.metroDataStore
import io.github.danielphingston.metro.ui.onboarding.tilesPerRowChoices
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.danielphingston.metro.ui.start.CoachMark
import io.github.danielphingston.metro.ui.start.HintPlace
import io.github.danielphingston.metro.ui.start.HintSignals
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The first-run setup's start screen choices and the hints after it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartSetupTest {
    @Test
    fun hintsComeOneAtATimeInOrderAndOnlyWhereTheyBelong() {
        assertEquals(CoachMark.HOLD_TILE, CoachMark.next(CoachMark.ALL, HintPlace.START))
        assertEquals(CoachMark.SWIPE_FOR_APPS, CoachMark.next(CoachMark.ALL and CoachMark.HOLD_TILE.bit.inv(), HintPlace.START))
        assertEquals(CoachMark.APP_LIST, CoachMark.next(CoachMark.ALL, HintPlace.APP_LIST))
        assertEquals(CoachMark.EDITING, CoachMark.next(CoachMark.ALL, HintPlace.EDITING))
        // Nothing over search or a menu, and nothing left to show.
        assertNull(CoachMark.next(CoachMark.ALL, null))
        assertNull(CoachMark.next(0, HintPlace.START))
        assertNull(CoachMark.next(CoachMark.EDITING.bit, HintPlace.START))
        // A hint a newer version stored is ignored, not shown or crashed on.
        assertNull(CoachMark.next(1 shl 20, HintPlace.START))
        assertTrue(CoachMark.pending(1 shl 20).isEmpty())
        // The bits are stored: they must never move.
        assertEquals(listOf(1, 2, 4, 8, 16), CoachMark.entries.map { it.bit })
        // Short enough for one or two lines in the bar.
        CoachMark.entries.forEach { assertTrue("${it.name} is too long", it.text.length <= 70) }
    }

    /** Signals backed by snapshot state, as the launcher screen's are. */
    private class FakeSignals : HintSignals {
        override var onAppList by mutableStateOf(false)
        override var editing by mutableStateOf(false)
        override var searching by mutableStateOf(false)
        override val place: HintPlace? get() = null
    }

    private fun seenAfter(mark: CoachMark, vararg steps: FakeSignals.() -> Unit): Boolean = runBlocking {
        val signals = FakeSignals()
        val waiting = launch { mark.awaitSeen(signals) }
        steps.forEach { step ->
            yield()
            Snapshot.withMutableSnapshot { signals.step() }
            Snapshot.sendApplyNotifications()
            repeat(3) { yield() }
        }
        val seen = waiting.isCompleted
        waiting.cancel()
        seen
    }

    @Test
    fun startHintsGoOnceTheirGestureIsDone() {
        assertTrue(seenAfter(CoachMark.HOLD_TILE, { editing = true }))
        assertTrue(seenAfter(CoachMark.SWIPE_FOR_APPS, { onAppList = true }))
        assertTrue(seenAfter(CoachMark.PULL_TO_SEARCH, { searching = true }))
        assertFalse(seenAfter(CoachMark.PULL_TO_SEARCH, { editing = true }))
    }

    @Test
    fun firstVisitHintsGoOnceTheirPlaceIsLeft() {
        assertFalse(seenAfter(CoachMark.APP_LIST, { onAppList = true }))
        assertTrue(seenAfter(CoachMark.APP_LIST, { onAppList = true }, { onAppList = false }))
        assertFalse(seenAfter(CoachMark.EDITING, { editing = true }))
        assertTrue(seenAfter(CoachMark.EDITING, { editing = true }, { editing = false }))
    }

    @Test
    fun tilesPerRowMatchTheSettingsPage() {
        // A phone offers two or three medium tiles per row: the "show more tiles" switch's 4 and 6 columns.
        assertEquals(listOf(4 to "2", 6 to "3"), tilesPerRowChoices(large = false))
        assertEquals(listOf(LauncherSettings.AUTO_COLUMNS, 4, 6, 8), tilesPerRowChoices(large = true).map { it.first })
        tilesPerRowChoices(large = true).forEach { (columns, _) -> assertEquals(columns, LauncherSettings.clampColumns(columns)) }
    }

    @Test
    fun hintsFollowDoneOnlyAndGoOnceSeen() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        // Every install that didn't just finish setup: none.
        assertEquals(0, settings.coachMarks.first())
        // Skipped (or a restored backup): done, and no hints.
        settings.finishOnboarding(0)
        assertEquals(true, settings.onboardingDone.first())
        assertEquals(0, settings.coachMarks.first())
        // "done": all of them, then each goes as it is seen, and the key with the last.
        settings.finishOnboarding(CoachMark.ALL)
        settings.clearCoachMarks(CoachMark.HOLD_TILE.bit)
        assertEquals(CoachMark.SWIPE_FOR_APPS, CoachMark.next(settings.coachMarks.first(), HintPlace.START))
        settings.clearCoachMarks(CoachMark.ALL)
        assertEquals(0, settings.coachMarks.first())
        assertFalse(context.metroDataStore.data.first().asMap().keys.any { it.name == "coach_marks" })
        // A wrongly typed value reads as none instead of crashing start.
        context.metroDataStore.edit { it[androidx.datastore.preferences.core.stringPreferencesKey("coach_marks")] = "x" }
        assertEquals(0, settings.coachMarks.first())
    }

    @Test
    fun hintsNeverTravelInABackup() {
        assertTrue("coach_marks" in Backup.EXCLUDED_KEYS)
        val json = Backup.encode(mutablePreferencesOf(intPreferencesKey("coach_marks") to CoachMark.ALL), "x", 0)
        assertFalse(json.contains("coach_marks"))
    }
}
