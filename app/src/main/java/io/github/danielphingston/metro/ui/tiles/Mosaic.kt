package io.github.danielphingston.metro.ui.tiles

import io.github.danielphingston.metro.data.TileSize
import kotlin.random.Random

/** The people tile's grid of square photos. */
internal data class MosaicGrid(val columns: Int, val rows: Int) {
    val cells: Int get() = columns * rows
}

/** Replace the photo in [cell] with the contact at [contact] in the pool. */
internal data class MosaicSwap(val cell: Int, val contact: Int)

internal object Mosaic {
    /** Three photos per medium tile edge, like the Windows Phone people hub; small shows one. */
    fun grid(size: TileSize): MosaicGrid = when (size) {
        TileSize.SMALL -> MosaicGrid(1, 1)
        TileSize.MEDIUM -> MosaicGrid(3, 3)
        TileSize.WIDE -> MosaicGrid(6, 3)
        TileSize.LARGE -> MosaicGrid(6, 6)
    }

    /** Pool index shown in each cell at first: the first contacts in order, -1 for empty cells. */
    fun initial(cells: Int, pool: Int): List<Int> = List(cells) { if (it < pool) it else -1 }

    /**
     * One random cell swap: a filled cell other than [lastCell] gets a contact that is not on
     * show. Null when every contact is already visible.
     */
    fun nextSwap(shown: List<Int>, pool: Int, lastCell: Int?, random: Random): MosaicSwap? {
        val spare = (0 until pool).filter { it !in shown }
        if (spare.isEmpty()) return null
        val filled = shown.indices.filter { shown[it] >= 0 }
        val candidates = filled.filter { it != lastCell }.ifEmpty { filled }
        if (candidates.isEmpty()) return null
        return MosaicSwap(candidates.random(random), spare.random(random))
    }
}
