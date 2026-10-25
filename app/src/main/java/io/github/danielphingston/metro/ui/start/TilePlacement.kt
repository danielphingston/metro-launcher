package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.TileSpan
import io.github.danielphingston.metro.data.TileSpec
import kotlin.math.roundToInt

/** A cell on the start grid. */
data class GridPos(val x: Int, val y: Int)

/** Tile [id] held with its top-left corner over [to]; see [TilePlacement.placeAt]. */
data class TileMove(val id: Long, val to: GridPos)

/**
 * The Windows Phone placement model as pure functions over the top-level tiles. Every tile owns
 * a position ([TileSpec.x], [TileSpec.y]) and only ever slides straight up: gravity never moves
 * a tile sideways, so changing one tile cannot send others into unrelated gaps.
 *
 * Footprints are in small-tile cells: [TileSpec.width] clamped to the column count and
 * [TileSpec.height] to 1..[TileSpan.MAX_HEIGHT]. A footprint with an even width of 2 or more
 * snaps to even columns (0, 2, 4), so medium, wide and large tiles line up with clusters of small
 * ones; odd widths may start on any column. Rows don't snap: any tile may start on any row, so a
 * tile rises into a row left half empty, such as under a pair of small tiles.
 *
 * [layout] is idempotent: feeding a settled layout back in changes nothing, which is what lets
 * the screen draw straight from the stored positions.
 */
object TilePlacement {
    /**
     * Where each of [specs] ends up in [columns] columns, in the same order:
     * 1. Positions are clamped into the grid and snapped.
     * 2. Overlaps are resolved top to bottom, then left to right: a tile that overlaps one before
     *    it is pushed down to just below it, repeatedly (later list entries lose ties). The tile
     *    with id [first] goes before all others, so it wins every overlap.
     * 3. Gravity, in the same order: each tile slides up while the cells above it are free.
     * 4. Unplaced tiles (see [TileSpec.isPlaced]) take, in list order, the first free spot in the
     *    last band of rows or below it, and then slide up like the rest.
     */
    fun layout(specs: List<TileSpec>, columns: Int, first: Long? = null): List<Placement> {
        val slots = specs.mapIndexed { index, spec -> Slot(index, spec, columns) }
        val fixed = slots.filter { it.spec.isPlaced }
        for (slot in fixed) {
            slot.x = slot.snapX(slot.spec.x)
            slot.y = slot.snapY(slot.spec.y)
        }
        val placed = ArrayList<Slot>()
        for (slot in fixed.sortedWith(compareBy<Slot>({ it.spec.id != first }, { it.y }, { it.x }, { it.index }))) {
            while (true) {
                val hit = placed.firstOrNull { slot.overlaps(it) } ?: break
                slot.y = hit.y + hit.height
            }
            placed += slot
        }
        val settled = ArrayList<Slot>()
        for (slot in placed.sortedWith(compareBy<Slot>({ it.y }, { it.x }, { it.index }))) {
            slot.fall(settled)
            settled += slot
        }
        for (slot in slots.filter { !it.spec.isPlaced }) {
            val spot = slot.firstFreeSpot(settled)
            slot.x = spot.x
            slot.y = spot.y
            slot.fall(settled)
            settled += slot
        }
        return slots.map { Placement(it.x, it.y, it.width, it.height) }
    }

    /** [specs] with their positions replaced by the [layout] they settle into. */
    fun settle(specs: List<TileSpec>, columns: Int, first: Long? = null): List<TileSpec> =
        specs.zip(layout(specs, columns, first)) { spec, p -> spec.copy(x = p.x, y = p.y) }

    /**
     * Gives positions to a layout saved before positions existed, once: when no tile is placed
     * yet they come from [TileGrid.pack] over the list order, so nothing moves on screen.
     */
    fun migrate(specs: List<TileSpec>, columns: Int): List<TileSpec> {
        if (specs.isEmpty() || specs.any { it.isPlaced }) return specs
        return specs.zip(TileGrid.pack(specs.map { it.size }, columns)) { spec, p -> spec.copy(x = p.x, y = p.y) }
    }

    /**
     * The cell where [spec]'s top-left corner snaps when it is at [cellX], [cellY] (pixels divided
     * by the cell size): the nearest cell, or the nearest even column for an even width, and not
     * outside the columns.
     */
    fun dropCell(spec: TileSpec, cellX: Float, cellY: Float, columns: Int): GridPos {
        val slot = Slot(0, spec, columns)
        val x = if (slot.evenX) (cellX / 2).roundToInt() * 2 else cellX.roundToInt()
        return GridPos(slot.snapX(x), slot.snapY(cellY.roundToInt()))
    }

    /** [specs] with [id] moved to [to] and nothing else settled; for [layout] with `first = id`, to preview a drag. */
    fun placeAt(specs: List<TileSpec>, id: Long, to: GridPos): List<TileSpec> =
        specs.map { if (it.id == id) it.copy(x = to.x, y = to.y) else it }

    /**
     * Drops tile [id] with its top-left corner on [to]. It wins the cell: tiles in its way are
     * pushed down below it, cascading, and the cells it left are filled by tiles rising from below.
     */
    fun moveTo(specs: List<TileSpec>, id: Long, to: GridPos, columns: Int): List<TileSpec> =
        settle(placeAt(specs, id, to), columns, first = id)

    /**
     * Steps tile [id] to its next size (dropping any custom span), keeping its top-left corner,
     * snapped and clamped if the new footprint needs it. Tiles that now overlap it are pushed
     * down below it, cascading. Nothing moves sideways, and the cells it frees stay empty unless
     * tiles directly below can rise into them.
     */
    fun resize(specs: List<TileSpec>, id: Long, columns: Int): List<TileSpec> =
        settle(specs.map { if (it.id == id) it.copy(size = it.size.next(), span = null) else it }, columns, first = id)

    /** As [resize], but gives tile [id] a custom footprint of [width] x [height] cells, clamped to the grid. */
    fun setSpan(specs: List<TileSpec>, id: Long, width: Int, height: Int, columns: Int): List<TileSpec> {
        val span = TileSpan(width.coerceIn(1, columns), height.coerceIn(1, TileSpan.MAX_HEIGHT))
        return settle(specs.map { if (it.id == id) it.copy(span = span) else it }, columns, first = id)
    }

    /**
     * Where tile [id] ends up, with its size, if it were given a custom footprint of [width] x
     * [height] cells (see [setSpan]): the snapped footprint a resize shows before it is applied.
     * Null if there is no such tile.
     */
    fun spanPlacement(specs: List<TileSpec>, id: Long, width: Int, height: Int, columns: Int): Placement? {
        val index = specs.indexOfFirst { it.id == id }
        if (index < 0) return null
        return layout(setSpan(specs, id, width, height, columns), columns, first = id)[index]
    }

    /** A tile's footprint and working position while the layout is computed. */
    private class Slot(val index: Int, val spec: TileSpec, columns: Int) {
        val width = spec.width.coerceIn(1, columns)
        val height = spec.height.coerceIn(1, TileSpan.MAX_HEIGHT)
        val evenX = width >= 2 && width % 2 == 0
        private val maxX = (columns - width).let { if (evenX) it / 2 * 2 else it }
        var x = 0
        var y = 0

        fun snapX(v: Int) = v.coerceIn(0, maxX).let { if (evenX) it / 2 * 2 else it }
        fun snapY(v: Int) = v.coerceAtLeast(0)

        fun overlaps(o: Slot) = overlaps(x, y, o)

        private fun overlaps(atX: Int, atY: Int, o: Slot) =
            atX < o.x + o.width && o.x < atX + width && atY < o.y + o.height && o.y < atY + height

        /** Slides up a row at a time while the row above is free in [settled]. */
        fun fall(settled: List<Slot>) {
            while (y >= 1 && settled.none { overlapsRows(it, y - 1, 1) }) y -= 1
        }

        /** Whether [o] is in the [rows] rows starting at [top], over this tile's columns. */
        private fun overlapsRows(o: Slot, top: Int, rows: Int) =
            x < o.x + o.width && o.x < x + width && top < o.y + o.height && o.y < top + rows

        /**
         * The first free spot in the last band of rows (the 2 rows holding the layout's bottom
         * edge, so a half-filled last row is completed) or below it. Small tiles fill 2x2
         * clusters; others scan left to right, row by row.
         */
        fun firstFreeSpot(settled: List<Slot>): GridPos {
            val bottom = settled.maxOfOrNull { it.y + it.height } ?: 0
            var row = if (bottom <= 0) 0 else (bottom - 1) / 2 * 2
            val small = width == 1 && height == 1
            while (true) {
                if (small) {
                    for (blockX in 0..maxX step 2) for (dy in 0..1) for (dx in 0..1) {
                        val cx = blockX + dx
                        if (cx <= maxX && settled.none { overlaps(cx, row + dy, it) }) return GridPos(cx, row + dy)
                    }
                } else {
                    for (cx in 0..maxX step if (evenX) 2 else 1) {
                        if (settled.none { overlaps(cx, row, it) }) return GridPos(cx, row)
                    }
                }
                row += if (small) 2 else 1
            }
        }
    }
}
