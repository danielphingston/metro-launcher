package io.github.danielphingston.metro.data

/** What tiles without a colour of their own use: the accent, or a colour taken from each app's icon. */
enum class TileColourMode(val label: String) {
    ACCENT("accent"),
    ICON("from each app's icon"),
}

/**
 * The per-tile colour ([TileSpec.color]): null follows [TileColourMode], [ACCENT] is always the
 * accent, [ICON] is derived from the tile's icon, and "#RRGGBB" is a fixed colour.
 */
object TileColors {
    const val ACCENT = "accent"
    const val ICON = "icon"

    private val HEX = Regex("#[0-9A-Fa-f]{6}")

    /** [raw] if it is a colour choice, else null: a garbled stored value falls back to the default. */
    fun parse(raw: String?): String? = when {
        raw == null -> null
        raw == ACCENT || raw == ICON -> raw
        HEX.matches(raw) -> raw.uppercase()
        else -> null
    }

    fun fixed(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    private fun fixedArgb(choice: String): Int? =
        if (HEX.matches(choice)) (choice.substring(1).toInt(16) or 0xFF000000.toInt()) else null

    private fun choice(color: String?, mode: TileColourMode) =
        color ?: if (mode == TileColourMode.ICON) ICON else ACCENT

    /** Whether the tile's colour comes from its icon, so the icon has to be read. */
    fun usesIcon(color: String?, mode: TileColourMode): Boolean = choice(color, mode) == ICON

    /**
     * The opaque colour that replaces the accent on a tile, or null to leave the accent. [icon]
     * is the tile's icon colour already adjusted for vividness (null if it has none).
     */
    fun resolve(color: String?, mode: TileColourMode, icon: Int?): Int? {
        val choice = choice(color, mode)
        return when (choice) {
            ACCENT -> null
            ICON -> icon
            else -> fixedArgb(choice)
        }
    }

    /** Sets the colour of tile [id] (top level or in a folder). */
    fun set(tiles: List<TileSpec>, id: Long, color: String?): List<TileSpec> = tiles.map { tile ->
        when {
            tile.id == id -> tile.copy(color = parse(color))
            tile.content is TileContent.Folder -> tile.copy(content = tile.content.copy(children = set(tile.content.children, id, color)))
            else -> tile
        }
    }
}
