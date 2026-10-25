package io.github.danielphingston.metro.data

/** What [TileFolders.groupIntoFolder] produced: the new tile list and the folder that now holds the grouped tiles. */
internal class FolderGroup(val tiles: List<TileSpec>, val folderId: Long)

/**
 * The folder edits as pure functions over the ordered tile list, so they can be tested without a
 * repository. A folder never keeps fewer than two children: with one it dissolves into that
 * child, with none it disappears.
 */
internal object TileFolders {
    const val DEFAULT_NAME = "folder"

    /** Only app and shortcut tiles can live in a folder. */
    fun canHold(spec: TileSpec): Boolean = spec.content is TileContent.App || spec.content is TileContent.Shortcut

    /** Whether [spec] can start a group: an app or shortcut (a new folder) or a folder (to add to). */
    fun canGroup(spec: TileSpec): Boolean = canHold(spec) || spec.content is TileContent.Folder

    /** Children are at most wide: a large tile would fill the whole open folder. They have no grid position. */
    private fun forFolder(spec: TileSpec): TileSpec =
        spec.copy(size = if (spec.size == TileSize.LARGE) TileSize.WIDE else spec.size, span = null).unplaced()

    private fun TileSpec.unplaced() = copy(x = TileSpec.UNPLACED, y = TileSpec.UNPLACED)

    /**
     * Groups the top-level tiles [ids] with [anchorId], in the order given. An anchor that is a
     * folder takes them as more children; an app or shortcut and them become a new folder with id
     * [newId] at the anchor's grid position, as big as the anchor unless that is small, holding the
     * anchor first. Ids that are missing, repeated, the anchor itself or not [canHold] are
     * ignored. Null when the anchor can't start a group or nothing is left to group.
     */
    fun groupIntoFolder(list: List<TileSpec>, anchorId: Long, ids: List<Long>, newId: Long): FolderGroup? {
        val anchor = list.firstOrNull { it.id == anchorId }?.takeIf(::canGroup) ?: return null
        val grouped = ids.distinct().filter { it != anchorId }
            .mapNotNull { id -> list.firstOrNull { it.id == id }?.takeIf(::canHold) }
        if (grouped.isEmpty()) return null
        val added = grouped.map(::forFolder)
        val folder = when (val content = anchor.content) {
            is TileContent.Folder -> anchor.copy(content = content.copy(children = content.children + added))
            else -> {
                val size = if (anchor.size == TileSize.SMALL) TileSize.MEDIUM else anchor.size
                TileSpec(newId, size, TileContent.Folder(DEFAULT_NAME, listOf(forFolder(anchor)) + added), anchor.x, anchor.y)
            }
        }
        val gone = grouped.mapTo(HashSet()) { it.id }
        val tiles = list.filterNot { it.id in gone }.map { if (it.id == anchorId) folder else it }
        return FolderGroup(tiles, folder.id)
    }

    /**
     * What stays on the start screen in place of [folder] when it is left holding [children]: a
     * single child takes the folder's spot.
     */
    private fun tidy(folder: TileSpec, children: List<TileSpec>): List<TileSpec> = when (children.size) {
        0 -> emptyList()
        1 -> listOf(children.single().copy(x = folder.x, y = folder.y))
        else -> listOf(folder.copy(content = (folder.content as TileContent.Folder).copy(children = children)))
    }

    /** Dissolves folders left with fewer than two children, e.g. after apps were uninstalled. */
    fun tidy(list: List<TileSpec>): List<TileSpec> = list.flatMap { spec ->
        val folder = spec.content as? TileContent.Folder
        if (folder == null) listOf(spec) else tidy(spec, folder.children)
    }

    /**
     * Takes [childId] out of folder [folderId] and puts it on the start screen as a new, unplaced
     * tile right after the folder in the list.
     */
    fun removeFromFolder(list: List<TileSpec>, folderId: Long, childId: Long): List<TileSpec> {
        val index = list.indexOfFirst { it.id == folderId }
        val folder = list.getOrNull(index)?.content as? TileContent.Folder ?: return list
        val child = folder.children.firstOrNull { it.id == childId } ?: return list
        val rest = folder.children.filterNot { it.id == childId }
        return list.take(index) + tidy(list[index], rest) + child.unplaced() + list.drop(index + 1)
    }

    /** Removes the tile [id] wherever it is, including from inside a folder. */
    fun remove(list: List<TileSpec>, id: Long): List<TileSpec> = tidy(
        list.filterNot { it.id == id }.map { spec ->
            val folder = spec.content as? TileContent.Folder ?: return@map spec
            spec.copy(content = folder.copy(children = folder.children.filterNot { it.id == id }))
        },
    )

    fun rename(list: List<TileSpec>, id: Long, name: String): List<TileSpec> = list.map { spec ->
        val folder = spec.content as? TileContent.Folder
        if (spec.id == id && folder != null) spec.copy(content = folder.copy(name = name.trim())) else spec
    }

    /** Moves child [id] into the slot of [targetId] inside folder [folderId]; the other children shift by one. */
    fun moveInFolder(list: List<TileSpec>, folderId: Long, id: Long, targetId: Long): List<TileSpec> =
        list.map { spec ->
            val folder = spec.content as? TileContent.Folder
            if (spec.id != folderId || folder == null) return@map spec
            val from = folder.children.indexOfFirst { it.id == id }
            val to = folder.children.indexOfFirst { it.id == targetId }
            if (from < 0 || to < 0 || from == to) return@map spec
            val moved = folder.children.toMutableList().apply { add(to, removeAt(from)) }
            spec.copy(content = folder.copy(children = moved))
        }
}
