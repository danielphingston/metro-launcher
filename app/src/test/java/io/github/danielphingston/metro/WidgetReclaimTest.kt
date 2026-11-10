package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.TileSpec
import io.github.danielphingston.metro.data.WidgetReclaim
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetReclaimTest {
    private fun widget(id: Long, widgetId: Int) =
        TileSpec(id, TileSize.MEDIUM, TileContent.Widget(widgetId, "com.example/.W", 0L))

    @Test
    fun deletesOnlyIdsNoTileReferences() {
        assertEquals(listOf(3, 5), WidgetReclaim.toDelete(listOf(1, 3, 4, 5), setOf(1, 4), undecoded = false))
    }

    @Test
    fun deletesNothingWhenSomeEntriesCouldNotBeDecoded() {
        assertEquals(emptyList<Int>(), WidgetReclaim.toDelete(listOf(1, 2), emptySet(), undecoded = true))
    }

    @Test
    fun widgetsInsideFoldersAreReferenced() {
        val folder = TileSpec(9, TileSize.MEDIUM, TileContent.Folder("f", listOf(widget(10, 7))))
        val referenced = WidgetReclaim.referencedIds(listOf(widget(1, 4), folder))
        assertEquals(setOf(4, 7), referenced)
        assertEquals(listOf(8), WidgetReclaim.toDelete(listOf(4, 7, 8), referenced, undecoded = false))
    }
}
