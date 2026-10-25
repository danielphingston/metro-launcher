package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.TileFolders
import io.github.danielphingston.metro.data.TileSpec

/**
 * Aiming a dragged tile at another to make or extend a folder, as pure functions over the tiles
 * as they are displayed. The dragged tile's centre decides: over a tile it can join, the other
 * tiles stop making room (so the target stays under the finger), and resting in that tile's
 * middle arms the merge after [DWELL_MILLIS].
 */
internal object MergeAim {
    /** How long the dragged tile must rest in a target's middle before dropping would merge them. */
    const val DWELL_MILLIS = 400L

    /** The middle of the target, as a fraction of its size, where the dragged tile's centre starts a merge. */
    const val ENTER_ZONE = 0.7f

    /** Once aimed at a target the zone widens, so a shaky finger doesn't restart the dwell on its edge. */
    const val STAY_ZONE = 0.9f

    /**
     * What the dragged tile ([dragged], an index into [specs] and [placements]) is aimed at with its
     * centre at ([cx], [cy]) in cells. [hover] is the tile under the centre that it could join, if
     * any; [joins] says the centre is also in its middle. [aimedId] is the tile aimed at already.
     */
    class Aim(val hover: Int?, val joins: Boolean)

    fun aim(specs: List<TileSpec>, placements: List<Placement>, dragged: Int, cx: Float, cy: Float, aimedId: Long?): Aim {
        val target = specs.indices.firstOrNull { it != dragged && placements[it].contains(cx, cy) }
        val hover = target?.takeIf { TileFolders.canMerge(specs[dragged], specs[it]) } ?: return Aim(null, false)
        val zone = if (specs[hover].id == aimedId) STAY_ZONE else ENTER_ZONE
        return Aim(hover, placements[hover].inMiddle(cx, cy, zone))
    }
}
