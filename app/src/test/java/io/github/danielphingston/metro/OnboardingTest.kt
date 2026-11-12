package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.onboarding.Onboarding
import io.github.danielphingston.metro.ui.onboarding.OnboardingStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {
    @Test
    fun showsOnlyOnAGenuineFirstRun() {
        assertTrue(Onboarding.shouldShow(layoutSaved = false, done = null))
    }

    @Test
    fun existingInstallsAndRestoredBackupsNeverSeeIt() {
        assertFalse(Onboarding.shouldShow(layoutSaved = true, done = null))
        assertFalse(Onboarding.shouldShow(layoutSaved = true, done = true))
        assertFalse(Onboarding.shouldShow(layoutSaved = false, done = true))
    }

    @Test
    fun runSetupAgainShowsWhateverTheLayout() {
        assertTrue(Onboarding.shouldShow(layoutSaved = true, done = false))
    }

    @Test
    fun stepsWalkForwardAndBackAndStopAtTheEnds() {
        assertEquals(OnboardingStep.WELCOME, OnboardingStep.entries.first())
        assertNull(OnboardingStep.WELCOME.previous)
        assertEquals(OnboardingStep.LIVE_TILES, OnboardingStep.WELCOME.next)
        assertEquals(OnboardingStep.LOOK, OnboardingStep.LIVE_TILES.next)
        assertEquals(OnboardingStep.LIVE_TILES, OnboardingStep.LOOK.previous)
        assertEquals(OnboardingStep.DONE, OnboardingStep.LOOK.next)
        assertTrue(OnboardingStep.DONE.isLast)
        assertNull(OnboardingStep.DONE.next)
        assertFalse(OnboardingStep.WELCOME.isLast)
    }
}
