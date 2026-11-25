package io.github.danielphingston.metro

import io.github.danielphingston.metro.ui.start.GridFrame
import io.github.danielphingston.metro.ui.start.LayerTransform
import io.github.danielphingston.metro.ui.start.Placement
import io.github.danielphingston.metro.ui.start.SwitchPlan
import io.github.danielphingston.metro.ui.start.TileRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchGeometryTest {
    private val eps = 0.001f

    private fun assertRect(expected: TileRect, actual: TileRect) {
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.width, actual.width, eps)
        assertEquals(expected.height, actual.height, eps)
    }

    /** What the layer draws for a tile laid out at [laidOut] with [t]. */
    private fun drawn(laidOut: TileRect, t: LayerTransform) =
        TileRect(laidOut.left + t.translationX, laidOut.top + t.translationY, laidOut.width * t.scaleX, laidOut.height * t.scaleY)

    // A 1080 px wide phone: two medium tiles per row (4 columns) with 8 px paddings, and three per row (6 columns) with 40 px.
    private val twoPerRow = GridFrame.centred(1080f, 8f, 1064f, 8f, topPx = 100f, cellPx = 266.5f, gapPx = 10f)
    private val threePerRow = GridFrame.centred(1080f, 40f, 1000f, 40f, topPx = 60f, cellPx = 168.33f, gapPx = 10f)

    @Test
    fun aFrameIsWhereStartLaysTheGridOut() {
        assertEquals(8f, twoPerRow.originX, eps)
        assertRect(TileRect(8f + 266.5f, 100f + 2 * 266.5f, 2 * 266.5f - 10f, 2 * 266.5f - 10f), twoPerRow.rectOf(Placement(1, 2, 2, 2)))
        // A grid narrower than the screen (a tablet) sits in the middle.
        assertEquals(240f, GridFrame.centred(1000f, 0f, 520f, 0f, 0f, 100f, 10f).originX, eps)
    }

    @Test
    fun theLayerDrawsTheLaidOutTileWhereItIsShown() {
        val laidOut = TileRect(10f, 20f, 100f, 50f)
        val shown = TileRect(-30f, 200f, 150f, 25f)
        assertRect(shown, drawn(laidOut, LayerTransform.between(laidOut, shown)))
        assertEquals(LayerTransform.NONE, LayerTransform.between(laidOut, laidOut))
    }

    @Test
    fun nothingThatCantBeDrawnBecomesATransform() {
        val fine = TileRect(0f, 0f, 10f, 10f)
        // A tile with no size (a grid measured at zero width) or a NaN must not reach the layer.
        assertEquals(LayerTransform.NONE, LayerTransform.between(TileRect(0f, 0f, 0f, 10f), fine))
        assertEquals(LayerTransform.NONE, LayerTransform.between(fine, TileRect(Float.NaN, 0f, 10f, 10f)))
        assertEquals(LayerTransform.NONE, LayerTransform.between(fine, TileRect(0f, 0f, Float.POSITIVE_INFINITY, 10f)))
        assertFalse(TileRect(0f, 0f, -1f, 10f).isUsable)
        assertTrue(fine.isUsable)
    }

    @Test
    fun tilesMoveSmoothlyFromTwoPerRowToThreePerRow() {
        val mail = Placement(2, 0, 2, 2) // right half of the top row with two per row...
        val mailNow = Placement(4, 2, 2, 2) // ...the third tile of the second row with three
        val gone = Placement(0, 4, 4, 2)
        val plan = SwitchPlan.of(mapOf(1L to ("mail" to mail), 2L to ("gone" to gone)), twoPerRow, setOf(1L, 3L))
        val laidOut = threePerRow.rectOf(mailNow)
        // It starts exactly where the old grid drew it, ends where the new one does, and is halfway between in the middle.
        val start = plan.frameOf(1L, laidOut, 0f)
        assertRect(twoPerRow.rectOf(mail), start.first)
        assertEquals(1f, start.second, eps)
        assertRect(laidOut, plan.frameOf(1L, laidOut, 1f).first)
        assertRect(twoPerRow.rectOf(mail).lerpTo(laidOut, 0.5f), plan.frameOf(1L, laidOut, 0.5f).first)
        // And the layer draws it there from its new place.
        assertRect(twoPerRow.rectOf(mail), drawn(laidOut, LayerTransform.between(laidOut, start.first)))

        // The tile that left is drawn where it was, fading and shrinking about its centre, gone before half way.
        val departure = plan.leavingByKey.getValue(2L)
        assertEquals("gone", departure.item)
        val half = SwitchPlan.LEAVING_END / 2
        val leaving = plan.frameOf(2L, threePerRow.rectOf(gone), half, departure.rect)
        assertRect(twoPerRow.rectOf(gone).scaledAboutCentre(0.9f), leaving.first)
        assertEquals(0.5f, leaving.second, eps)
        assertEquals(0f, plan.frameOf(2L, threePerRow.rectOf(gone), SwitchPlan.LEAVING_END, departure.rect).second, eps)

        // The new tile appears late, growing into where it belongs.
        val newRect = threePerRow.rectOf(Placement(0, 0, 2, 2))
        assertEquals(setOf(3L), plan.entering)
        assertEquals(0f, plan.frameOf(3L, newRect, SwitchPlan.ENTERING_START).second, eps)
        assertRect(newRect.scaledAboutCentre(SwitchPlan.ENTERING_SCALE), plan.frameOf(3L, newRect, SwitchPlan.ENTERING_START).first)
        assertRect(newRect, plan.frameOf(3L, newRect, 1f).first)
        assertEquals(1f, plan.frameOf(3L, newRect, 1f).second, eps)
        assertEquals(0f, plan.frameOf(3L, newRect, -1f).second, eps)
    }

    @Test
    fun leavingAndNewTilesBarelyOverlap() {
        // When the new tiles start to show, the leaving ones are nearly gone, and they are gone soon after.
        assertTrue(SwitchPlan.ENTERING_START < SwitchPlan.LEAVING_END)
        assertTrue(SwitchPlan.LEAVING_END - SwitchPlan.ENTERING_START <= 0.15f)
    }

    @Test
    fun aScrolledStartKeepsItsTilesWhereTheyWereOnScreen() {
        // Scrolled 500 px down, then jumped to the top as the switch begins: the old rectangles move up by as much.
        val placement = Placement(0, 6, 2, 2)
        val plan = SwitchPlan.of(mapOf(1L to ("mail" to placement), 2L to ("gone" to placement)), twoPerRow, setOf(1L), scrolledPx = 500f)
        val onScreen = twoPerRow.rectOf(placement).let { it.copy(top = it.top - 500f) }
        assertRect(onScreen, plan.frameOf(1L, threePerRow.rectOf(Placement(0, 0, 2, 2)), 0f).first)
        assertRect(onScreen, plan.leavingByKey.getValue(2L).rect)
    }

    @Test
    fun aSwitchDuringASwitchStartsFromWhereTilesAreDrawn() {
        val first = SwitchPlan.of(mapOf(1L to ("mail" to Placement(0, 0, 2, 2))), twoPerRow, setOf(1L))
        val target = Placement(4, 0, 2, 2)
        // Halfway through the first switch, the second one starts: the tile is drawn halfway.
        val halfway = first.frameOf(1L, threePerRow.rectOf(target), 0.5f).first
        val second = SwitchPlan.of(mapOf(1L to ("mail" to target)), threePerRow, setOf(1L)) { key, laidOut ->
            first.frameOf(key, laidOut, 0.5f).first
        }
        assertRect(halfway, second.frameOf(1L, twoPerRow.rectOf(Placement(0, 2, 2, 2)), 0f).first)
    }

    @Test
    fun aTileTheSwitchDoesntKnowIsDrawnWhereItIs() {
        val plan = SwitchPlan.none<String>()
        val rect = threePerRow.rectOf(Placement(0, 0, 1, 1))
        assertEquals(rect to 1f, plan.frameOf(9L, rect, 0.3f))
        assertTrue(plan.leaving.isEmpty() && plan.from.isEmpty() && plan.entering.isEmpty())
    }
}
