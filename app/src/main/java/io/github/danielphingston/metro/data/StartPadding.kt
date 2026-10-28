package io.github.danielphingston.metro.data

/** The start screen's paddings (dp) after fitting them to the screen they are drawn on. */
data class StartPadding(val top: Float, val bottom: Float, val left: Float, val right: Float) {
    companion object {
        /** The least width (dp) a tile column may shrink to, whatever the side paddings. */
        const val MIN_CELL_DP = 40f

        /** The least height (dp) of the screen, between the system bars, left for the tiles. */
        const val MIN_VISIBLE_DP = 200f

        /**
         * The settings' paddings, scaled down together when they would leave less than [MIN_CELL_DP]
         * per column of [columns] (the shown count, which [settings] may leave to the screen) in [availableWidthDp] (the width without the system's side insets) or less than
         * [MIN_VISIBLE_DP] of [availableHeightDp] (the height without the status and navigation bars).
         * The defaults are always allowed, so small screens look as they always did.
         */
        fun fit(
            settings: LauncherSettings,
            availableWidthDp: Float,
            availableHeightDp: Float,
            tileGapDp: Float,
            columns: Int = settings.columns.coerceAtLeast(LauncherSettings.MIN_COLUMNS),
        ): StartPadding {
            val horizontal = fitPair(
                LauncherSettings.clampStartSidePadding(settings.startLeftPadding).toFloat(),
                LauncherSettings.clampStartSidePadding(settings.startRightPadding).toFloat(),
                limit = availableWidthDp - columns * MIN_CELL_DP + tileGapDp,
                floor = (LauncherSettings.DEFAULT_START_LEFT_PADDING + LauncherSettings.DEFAULT_START_RIGHT_PADDING).toFloat(),
            )
            val vertical = fitPair(
                LauncherSettings.clampStartTopPadding(settings.startTopPadding).toFloat(),
                LauncherSettings.clampStartBottomPadding(settings.startBottomPadding).toFloat(),
                limit = availableHeightDp - MIN_VISIBLE_DP,
                floor = (LauncherSettings.DEFAULT_START_TOP_PADDING + LauncherSettings.DEFAULT_START_BOTTOM_PADDING).toFloat(),
            )
            return StartPadding(vertical.first, vertical.second, horizontal.first, horizontal.second)
        }

        private fun fitPair(a: Float, b: Float, limit: Float, floor: Float): Pair<Float, Float> {
            val allowed = maxOf(limit, floor)
            val sum = a + b
            return if (sum <= allowed) a to b else (a * allowed / sum) to (b * allowed / sum)
        }
    }
}
