package io.github.danielphingston.metro.ui.start

import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.ui.tiles.WidgetTiles
import kotlin.math.ceil

/** How many columns the start grid shows, which with the automatic setting depends on the width. */
object StartColumns {
    /** The most columns the automatic setting picks: a tablet in landscape needs about 14. */
    const val MAX_AUTO = 16

    /** Slack for float error when the width holds a whole number of cells. */
    private const val EPSILON = 0.001f

    /**
     * The smallest even number of columns (at least 4, at most [MAX_AUTO]) whose cells are no wider
     * than [WidgetTiles.MAX_CELL_DP]. [widthDp] is the width of the grid plus one tile gap
     * (a column takes its cell less the gap), so the tiles stay about a phone's size and the grid
     * still reaches both edges.
     */
    fun auto(widthDp: Float): Int {
        val needed = ceil(widthDp / WidgetTiles.MAX_CELL_DP - EPSILON).toInt()
        return ((needed + 1) / 2 * 2).coerceIn(LauncherSettings.MIN_COLUMNS, MAX_AUTO)
    }

    /**
     * The columns to show for the stored [setting]. Automatic ([LauncherSettings.AUTO_COLUMNS])
     * fills the width on a [large] screen and is the usual 4 on a phone, where it can only have
     * come from a backup of a tablet.
     */
    fun resolve(setting: Int, large: Boolean, widthDp: Float): Int = when {
        setting != LauncherSettings.AUTO_COLUMNS -> setting
        large -> auto(widthDp)
        else -> LauncherSettings.DEFAULT_COLUMNS
    }
}
