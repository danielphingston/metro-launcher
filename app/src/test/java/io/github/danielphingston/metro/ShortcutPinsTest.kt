package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.tiles.ShortcutPins
import org.junit.Assert.assertEquals
import org.junit.Test

class ShortcutPinsTest {
    @Test
    fun addedAppendsNewId() {
        assertEquals(listOf("a", "b", "c"), ShortcutPins.added(listOf("a", "b"), "c"))
    }

    @Test
    fun addedKeepsPinnedIdOnce() {
        assertEquals(listOf("a", "b"), ShortcutPins.added(listOf("a", "b"), "a"))
    }

    @Test
    fun removedDropsOnlyThatId() {
        assertEquals(listOf("a", "c"), ShortcutPins.removed(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun removedLastIdLeavesNothingPinned() {
        assertEquals(emptyList<String>(), ShortcutPins.removed(listOf("a"), "a"))
        assertEquals(emptyList<String>(), ShortcutPins.removed(emptyList(), "a"))
    }
}
