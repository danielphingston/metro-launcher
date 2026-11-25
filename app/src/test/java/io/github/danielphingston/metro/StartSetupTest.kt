package io.github.danielphingston.metro

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import io.github.danielphingston.metro.data.Backup
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.metroDataStore
import io.github.danielphingston.metro.ui.onboarding.StartSpacing
import io.github.danielphingston.metro.ui.onboarding.tilesPerRowChoices
import io.github.danielphingston.metro.ui.start.CoachMark
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
    fun hintsComeOneAtATimeInOrder() {
        assertEquals(CoachMark.HOLD_TILE, CoachMark.next(CoachMark.ALL))
        assertEquals(CoachMark.SWIPE_FOR_APPS, CoachMark.next(CoachMark.ALL and CoachMark.HOLD_TILE.bit.inv()))
        assertEquals(CoachMark.PULL_TO_SEARCH, CoachMark.next(CoachMark.PULL_TO_SEARCH.bit))
        assertNull(CoachMark.next(0))
        // A hint a newer version stored is ignored, not shown or crashed on.
        assertNull(CoachMark.next(1 shl 20))
        // The bits are stored: they must never move.
        assertEquals(listOf(1, 2, 4), CoachMark.entries.map { it.bit })
    }

    @Test
    fun spacingPresetsAreReadBackOnlyWhenBothSidesMatch() {
        assertEquals(StartSpacing.NORMAL, StartSpacing.of(LauncherSettings()))
        assertEquals(StartSpacing.ROOMY, StartSpacing.of(LauncherSettings(startLeftPadding = 20, startRightPadding = 20)))
        assertNull(StartSpacing.of(LauncherSettings(startLeftPadding = 20, startRightPadding = 8)))
        // Every preset is a padding settings accepts as it is.
        StartSpacing.entries.forEach { assertEquals(it.sideDp, LauncherSettings.clampStartSidePadding(it.sideDp)) }
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
        assertEquals(CoachMark.SWIPE_FOR_APPS, CoachMark.next(settings.coachMarks.first()))
        settings.clearCoachMarks(CoachMark.SWIPE_FOR_APPS.bit or CoachMark.PULL_TO_SEARCH.bit)
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
