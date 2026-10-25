package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.ui.start.MergeAim
import io.github.danielphingston.metro.ui.start.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeAimTest {
    private fun app(id: Long) = TileSpec(id, TileSize.MEDIUM, TileContent.App(AppKey("com.example.$id", "Main", 0)))

    private val specs = listOf(app(1), app(2), TileSpec(3, TileSize.MEDIUM, TileContent.Weather))

    /** The dragged tile 1 and two medium tiles beside it; tile 3 can't be merged into. */
    private val placements = listOf(Placement(0, 0, 2, 2), Placement(2, 0, 2, 2), Placement(0, 2, 2, 2))

    private fun aim(cx: Float, cy: Float, aimedId: Long? = null) = MergeAim.aim(specs, placements, 0, cx, cy, aimedId)

    @Test
    fun theMiddleOfATileJoinsIt() {
        val aim = aim(3f, 1f)
        assertEquals(1, aim.hover)
        assertTrue(aim.joins)
    }

    @Test
    fun theZoneIsAboutSevenTenthsOfTheTile() {
        // 0.7 of a 2 cell tile: within 0.7 cells of the centre on either axis.
        assertTrue(aim(3.6f, 1.6f).joins)
        assertTrue(aim(2.4f, 0.4f).joins)
        assertFalse(aim(3.8f, 1f).joins)
        assertFalse(aim(3f, 1.8f).joins)
        // The old half-size zone would have missed this one.
        assertTrue(aim(3.6f, 1f).joins)
    }

    @Test
    fun overTheRimOfATileItIsHoveredButNotJoined() {
        val aim = aim(3.9f, 1f)
        assertEquals(1, aim.hover)
        assertFalse(aim.joins)
    }

    @Test
    fun theZoneWidensOnceAimedSoAShakyFingerKeepsTheDwell() {
        assertFalse(aim(3.8f, 1f, aimedId = null).joins)
        assertTrue(aim(3.8f, 1f, aimedId = 2).joins)
        // Aimed at another tile does not widen this one.
        assertFalse(aim(3.8f, 1f, aimedId = 3).joins)
        // Still has an edge.
        assertFalse(aim(3.95f, 1f, aimedId = 2).joins)
    }

    @Test
    fun tilesThatCannotBeJoinedAreNeitherHoveredNorJoined() {
        val aim = aim(1f, 3f)
        assertNull(aim.hover)
        assertFalse(aim.joins)
    }

    @Test
    fun overNothingOrOnlyItselfNothingIsAimedAt() {
        assertNull(aim(1f, 1f).hover)
        assertNull(aim(9f, 9f).hover)
    }

    @Test
    fun theDwellIsAboutFourTenthsOfASecond() {
        assertTrue(MergeAim.DWELL_MILLIS in 350L..400L)
    }
}
