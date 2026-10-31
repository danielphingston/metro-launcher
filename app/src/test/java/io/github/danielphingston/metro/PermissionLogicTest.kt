package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.settings.PermissionLogic
import io.github.danielphingston.metro.ui.settings.PermissionStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionLogicTest {
    private val contacts = "android.permission.READ_CONTACTS"
    private val calendar = "android.permission.READ_CALENDAR"

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
    fun onlyTheRequiredPermissionCountsAsGranted() {
        assertEquals(true, PermissionLogic.satisfied(mapOf(calendar to true, contacts to true), contacts))
        assertEquals(false, PermissionLogic.satisfied(mapOf(calendar to true, contacts to false), contacts))
        assertEquals(false, PermissionLogic.satisfied(emptyMap(), contacts))
    }
}
