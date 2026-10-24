package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.ui.applist.AppGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupsTest {
    @Test
    fun groupsByFoldedFirstLetter() {
        assertEquals("c", AppGroups.groupOf("Camera"))
        assertEquals("e", AppGroups.groupOf("Écran"))
        assertEquals("#", AppGroups.groupOf("7-Zip"))
        assertEquals("a", AppGroups.groupOf("\"Amazing\" app"))
        assertEquals(AppGroups.OTHER, AppGroups.groupOf("微信"))
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        assertTrue(AppGroups.matches("Café Finder", "cafe"))
        assertTrue(AppGroups.matches("WhatsApp", "APP"))
        assertFalse(AppGroups.matches("Maps", "mail"))
    }

    @Test
    fun appKeyRoundTrips() {
        val key = AppKey("com.example.app", "com.example.app.MainActivity", 10)
        assertEquals(key, AppKey.parse(key.serialize()))
        assertEquals(null, AppKey.parse("garbage"))
    }
}
