package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.settings.PermissionLogic
import io.github.danielphingston.metro.ui.settings.PermissionStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLogicTest {
    private val fine = "android.permission.ACCESS_FINE_LOCATION"
    private val coarse = "android.permission.ACCESS_COARSE_LOCATION"

    @Test
    fun grantedJustSaves() {
        assertEquals(PermissionStep.SAVE, PermissionLogic.step(granted = true, permanentlyDenied = false))
        assertEquals(PermissionStep.SAVE, PermissionLogic.step(granted = true, permanentlyDenied = true))
    }

    @Test
    fun aPermanentDenialOpensAppInfoInsteadOfARequestThatDoesNothing() {
        assertEquals(PermissionStep.REQUEST, PermissionLogic.step(granted = false, permanentlyDenied = false))
        assertEquals(PermissionStep.OPEN_APP_INFO, PermissionLogic.step(granted = false, permanentlyDenied = true))
    }

    @Test
    fun denialIsPermanentOnlyWithoutARationale() {
        assertEquals(true, PermissionLogic.permanentlyDenied(denied = true, shouldShowRationale = false))
        assertEquals(false, PermissionLogic.permanentlyDenied(denied = true, shouldShowRationale = true))
        assertEquals(false, PermissionLogic.permanentlyDenied(denied = false, shouldShowRationale = false))
    }

    @Test
    fun onlyPreciseLocationCountsAsGranted() {
        assertEquals(true, PermissionLogic.satisfied(mapOf(coarse to true, fine to true), fine))
        assertEquals(false, PermissionLogic.satisfied(mapOf(coarse to true, fine to false), fine))
        assertEquals(false, PermissionLogic.satisfied(emptyMap(), fine))
    }
}
