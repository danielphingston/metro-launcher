package io.github.danielphingston.metro.data

/** What the tile's picked photo ([TileSpec.image]) is used for. */
enum class PhotoUse {
    NONE,

    /** The photo replaces the icon ("my photo"). */
    ICON,

    /** The photo fills the tile; the icon (when shown) is drawn on it. */
    FILL,
}

/**
 * How a tile with an icon and a name draws itself, as the customise sheet shows it: two toggles
 * and what the photo is for. A folder's "icon" is its grid of app icons.
 *
 * Stored as the closest [TileStyle] (so older versions read it as nearly as they can) plus
 * [TileSpec.hideName] and [TileSpec.hideIcon] only where the style alone means something else;
 * see [TileLooks].
 */
data class TileLook(val showName: Boolean = true, val showIcon: Boolean = true, val photo: PhotoUse = PhotoUse.NONE) {
    /** A tile must show something: its name, its icon, or a photo filling it. */
    val isValid: Boolean get() = showName || showIcon || photo == PhotoUse.FILL
}

/** The mapping between [TileStyle] (plus the hide flags) and [TileLook], both ways. */
object TileLooks {
    /** The style and flags that store a look; see [stored]. */
    data class Stored(val style: TileStyle, val hideName: Boolean = false, val hideIcon: Boolean = false)

    /**
     * What [style] alone means. App, shortcut and search tiles: "name only" has no icon, "full tile
     * image" draws the photo and the name without the icon, "image behind icon" draws the icon on
     * the photo, "custom icon" uses the photo as the icon. A folder never shows a photo as its icon,
     * and its "full tile image" is the photo alone (the grid and name appear when it opens).
     */
    fun base(style: TileStyle, folder: Boolean = false): TileLook = if (folder) {
        when (style) {
            TileStyle.ICON -> TileLook(showName = true, showIcon = true)
            TileStyle.IMAGE_ICON -> TileLook(showName = true, showIcon = true, photo = PhotoUse.FILL)
            TileStyle.FULL_IMAGE -> TileLook(showName = false, showIcon = false, photo = PhotoUse.FILL)
            // Never offered for folders; a folder drew these as its grid without a name.
            TileStyle.ICON_ONLY, TileStyle.NAME_ONLY, TileStyle.CUSTOM_ICON -> TileLook(showName = false, showIcon = true)
        }
    } else {
        when (style) {
            TileStyle.ICON -> TileLook(showName = true, showIcon = true)
            TileStyle.ICON_ONLY -> TileLook(showName = false, showIcon = true)
            TileStyle.NAME_ONLY -> TileLook(showName = true, showIcon = false)
            TileStyle.CUSTOM_ICON -> TileLook(showName = true, showIcon = true, photo = PhotoUse.ICON)
            TileStyle.FULL_IMAGE -> TileLook(showName = true, showIcon = false, photo = PhotoUse.FILL)
            TileStyle.IMAGE_ICON -> TileLook(showName = true, showIcon = true, photo = PhotoUse.FILL)
        }
    }

    /** The look stored as [style] with the hide flags. Flags that would leave the tile blank are ignored. */
    fun of(style: TileStyle, hideName: Boolean, hideIcon: Boolean, folder: Boolean = false): TileLook {
        val base = base(style, folder)
        val look = base.copy(showName = base.showName && !hideName, showIcon = base.showIcon && !hideIcon)
        return if (look.isValid) look else base
    }

    /**
     * The closest style for [look] and the flags it needs on top. Every look an old style can
     * express is stored as that style alone; the others take the nearest style and the flags.
     * An invalid look is stored as the nearest valid one (the icon comes back).
     */
    fun stored(look: TileLook, folder: Boolean = false): Stored {
        val l = normalized(look, folder)
        val (name, icon) = l.showName to l.showIcon
        return if (folder) {
            when (l.photo) {
                PhotoUse.FILL -> when {
                    name && icon -> Stored(TileStyle.IMAGE_ICON)
                    !name && !icon -> Stored(TileStyle.FULL_IMAGE)
                    else -> Stored(TileStyle.IMAGE_ICON, hideName = !name, hideIcon = !icon)
                }
                else -> when {
                    name && icon -> Stored(TileStyle.ICON)
                    icon -> Stored(TileStyle.ICON_ONLY)
                    else -> Stored(TileStyle.ICON, hideIcon = true)
                }
            }
        } else {
            when (l.photo) {
                PhotoUse.NONE -> when {
                    name && icon -> Stored(TileStyle.ICON)
                    icon -> Stored(TileStyle.ICON_ONLY)
                    else -> Stored(TileStyle.NAME_ONLY)
                }
                PhotoUse.ICON -> Stored(TileStyle.CUSTOM_ICON, hideName = !name, hideIcon = !icon)
                PhotoUse.FILL -> Stored(if (icon) TileStyle.IMAGE_ICON else TileStyle.FULL_IMAGE, hideName = !name)
            }
        }
    }

    /** [look] as it can be stored: a folder has no photo icon, and a blank tile gets its icon back. */
    fun normalized(look: TileLook, folder: Boolean = false): TileLook {
        val l = if (folder && look.photo == PhotoUse.ICON) look.copy(photo = PhotoUse.NONE) else look
        return if (l.isValid) l else l.copy(showIcon = true)
    }

    /**
     * The look a tile is drawn with: a photo style whose photo is missing draws as the plain icon
     * tile, as it always has (a folder's "full tile image" keeps its grid without a name).
     */
    fun shown(spec: TileSpec): TileLook {
        val folder = spec.content is TileContent.Folder
        val style = when {
            !spec.style.needsImage || spec.image != null -> spec.style
            folder && spec.style == TileStyle.FULL_IMAGE -> TileStyle.ICON_ONLY
            else -> TileStyle.ICON
        }
        return of(style, spec.hideName, spec.hideIcon, folder)
    }
}

/** The settings the customise sheet can offer for a tile. */
enum class TileOption {
    /** "Show name": the tile's name label (on live tiles only the small label). */
    SHOW_NAME,

    /** "Show icon" (a folder's grid of app icons). */
    SHOW_ICON,

    /** App icon, one from an icon pack, or my photo. */
    ICON_SOURCE,

    /** No photo, or a photo filling the tile. */
    PHOTO,

    /** "Show background": the tile surface, or only the content's own look. */
    BACKGROUND,
    COLOUR,
    TRANSPARENCY,

    /** The hosted widget's own settings activity. */
    WIDGET_SETTINGS,

    /** Group into a folder (or add to this folder): a badge, and a TalkBack action. */
    GROUP,
}

object TileOptions {
    private val SURFACE = setOf(TileOption.BACKGROUND, TileOption.COLOUR, TileOption.TRANSPARENCY)
    private val LOOK = setOf(TileOption.SHOW_NAME, TileOption.SHOW_ICON, TileOption.ICON_SOURCE, TileOption.PHOTO)

    /**
     * What applies to [spec]. [inFolder]: it is a folder's child (which can't start a group).
     * [ownFace]: an app tile drawn as a live face that has no icon (the clock and calendar apps),
     * so only its name label and surface can change. [widgetSettings]: the widget's provider has
     * a configuration activity.
     */
    fun available(
        spec: TileSpec,
        inFolder: Boolean = false,
        ownFace: Boolean = false,
        widgetSettings: Boolean = false,
    ): Set<TileOption> {
        val options = LinkedHashSet<TileOption>()
        when (spec.content) {
            is TileContent.App -> options += if (ownFace) setOf(TileOption.SHOW_NAME) else LOOK
            is TileContent.Shortcut, is TileContent.Action -> options += LOOK
            is TileContent.Folder -> options += setOf(TileOption.SHOW_NAME, TileOption.SHOW_ICON, TileOption.PHOTO)
            is TileContent.Widget -> Unit
            TileContent.Weather, TileContent.Photos, TileContent.People, is TileContent.QuickSetting,
            TileContent.Assistant, TileContent.Me, is TileContent.LayoutSwitch,
            -> options += TileOption.SHOW_NAME
        }
        options += SURFACE
        if (spec.content is TileContent.Widget && widgetSettings) options += TileOption.WIDGET_SETTINGS
        if (!inFolder && TileFolders.canGroup(spec)) options += TileOption.GROUP
        return options
    }

    /**
     * Whether "show name" ([name] true) or "show icon" can be switched off for [spec] right now:
     * not when it is the last thing the tile shows.
     */
    fun canHide(spec: TileSpec, name: Boolean): Boolean {
        val look = spec.look
        val after = if (name) look.copy(showName = false) else look.copy(showIcon = false)
        return after.isValid
    }

    /** [tiles] with tile [id] (top level or in a folder) replaced by [change] of it. */
    fun update(tiles: List<TileSpec>, id: Long, change: (TileSpec) -> TileSpec): List<TileSpec> = tiles.map { tile ->
        when {
            tile.id == id -> change(tile)
            tile.content is TileContent.Folder -> tile.copy(content = tile.content.copy(children = update(tile.content.children, id, change)))
            else -> tile
        }
    }

    /**
     * [spec] drawn in [look], stored as the closest style plus flags, with [image] kept only while
     * the look uses a photo.
     */
    fun withLook(spec: TileSpec, look: TileLook, image: String?): TileSpec {
        val stored = TileLooks.stored(look, spec.content is TileContent.Folder)
        return spec.copy(style = stored.style, hideName = stored.hideName, hideIcon = stored.hideIcon, image = image.takeIf { stored.style.needsImage })
    }

    /**
     * [spec] with its name shown or hidden. A tile with the icon-and-name look changes its look; any
     * other (a live face) only its flag, so its style, and what older versions show, stays.
     */
    fun withNameShown(spec: TileSpec, shown: Boolean, hasLook: Boolean): TileSpec =
        if (hasLook) withLook(spec, spec.look.copy(showName = shown), spec.image) else spec.copy(hideName = !shown)

    /** [spec] with its icon shown or hidden. */
    fun withIconShown(spec: TileSpec, shown: Boolean): TileSpec = withLook(spec, spec.look.copy(showIcon = shown), spec.image)

    /**
     * [spec] with a picked icon (null: its own). A photo used as the icon gives way to it, since
     * there is one icon.
     */
    fun withIcon(spec: TileSpec, icon: String?): TileSpec {
        val look = spec.look
        val base = if (look.photo == PhotoUse.ICON) withLook(spec, look.copy(photo = PhotoUse.NONE), null) else spec
        return base.copy(icon = icon)
    }

    /** [spec] with the photo [image] used as [use] ([PhotoUse.NONE] drops it). */
    fun withPhoto(spec: TileSpec, use: PhotoUse, image: String?): TileSpec =
        withLook(spec, spec.look.copy(photo = use), image.takeIf { use != PhotoUse.NONE })

    /** [spec] with its background shown or hidden. A widget keeps it in its own flag, as it always has. */
    fun withBackground(spec: TileSpec, shown: Boolean): TileSpec {
        val widget = spec.content as? TileContent.Widget
        return if (widget != null) spec.copy(content = widget.copy(transparent = !shown), background = true) else spec.copy(background = shown)
    }

    /** [spec] with its own transparency, 0..100 percent; null follows the start screen setting. */
    fun withTransparency(spec: TileSpec, percent: Int?): TileSpec = spec.copy(transparency = percent?.coerceIn(0, 100))
}
