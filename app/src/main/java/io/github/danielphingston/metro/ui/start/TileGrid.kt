package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.TileSize
import kotlin.math.abs

/** A tile's position on the start grid, in small-tile cells. */
data class Placement(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(cellX: Float, cellY: Float) =
        cellX >= x && cellX < x + width && cellY >= y && cellY < y + height

    fun overlaps(other: Placement) =
        x < other.x + other.width && other.x < x + width && y < other.y + other.height && other.y < y + height

    /** Whether the point is within the middle [fraction] of the tile on both axes. */
    fun inMiddle(cellX: Float, cellY: Float, fraction: Float = 0.5f): Boolean =
        abs(cellX - (x + width / 2f)) <= width * fraction / 2 &&
            abs(cellY - (y + height / 2f)) <= height * fraction / 2
}

/** First-fit packing of an ordered list; the start screen itself is laid out by [TilePlacement]. */
object TileGrid {
    /**
     * Places tiles in order at the first free spot. Tiles of 2+ cells snap to even columns and
     * rows; small tiles fill 2x2 blocks (left-right, then top-bottom) the way they did on
     * Windows Phone, so four small tiles take the space of one medium tile. Later tiles may
     * fill gaps left by earlier ones. It gives positions to layouts saved without them
     * ([TilePlacement.migrate]) and lays out the children of an open folder.
     */
    fun pack(sizes: List<TileSize>, columns: Int): List<Placement> {
        val occupied = ArrayList<BooleanArray>()
        fun isFree(x: Int, y: Int, w: Int, h: Int): Boolean {
            if (x + w > columns) return false
            for (row in y until y + h) {
                val cells = occupied.getOrNull(row) ?: continue
                for (col in x until x + w) if (cells[col]) return false
            }
            return true
        }
        return sizes.map { size ->
            val w = size.width.coerceAtMost(columns)
            val h = size.height
            val placed = if (w == 1 && h == 1) placeSmall(columns, ::isFree) else placeLarge(w, h, columns, ::isFree)
            while (occupied.size < placed.y + h) occupied.add(BooleanArray(columns))
            for (row in placed.y until placed.y + h) for (col in placed.x until placed.x + w) occupied[row][col] = true
            placed
        }
    }

    private inline fun placeSmall(columns: Int, isFree: (Int, Int, Int, Int) -> Boolean): Placement {
        var blockY = 0
        while (true) {
            for (blockX in 0 until columns step 2) {
                for (dy in 0..1) for (dx in 0..1) {
                    if (isFree(blockX + dx, blockY + dy, 1, 1)) return Placement(blockX + dx, blockY + dy, 1, 1)
                }
            }
            blockY += 2
        }
    }

    private inline fun placeLarge(w: Int, h: Int, columns: Int, isFree: (Int, Int, Int, Int) -> Boolean): Placement {
        var y = 0
        while (true) {
            for (x in 0..columns - w step 2) if (isFree(x, y, w, h)) return Placement(x, y, w, h)
            y += 2
        }
    }

    fun rows(placements: List<Placement>): Int = placements.maxOfOrNull { it.y + it.height } ?: 0
}
