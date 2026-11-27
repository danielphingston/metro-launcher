package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** What the field picker is picking for. */
internal enum class PickMode {
    /** A value for a tile part: plain values add to the template; a list can also become the rows. */
    FIELDS,

    /** A value of each list item, for a row: the tree is one item. */
    ROW,

    /** The list shown as rows: only lists can be picked. */
    LIST,
}

/** One line the picker shows: an entry of the tree at [depth], or a "more" line under [parent]. */
private sealed interface TreeLine {
    data class Entry(val node: ApiJson.Node, val depth: Int) : TreeLine
    data class More(val parent: String, val hidden: Int, val depth: Int) : TreeLine

    /** A list's items past the first few: rows show them all; search finds values in them. */
    data class Items(val hidden: Int, val depth: Int) : TreeLine
}

/** An object shows this many fields before a "show more" line. */
private const val FIELDS_SHOWN = 60

/**
 * The fields of a fetched answer, for picking what a tile shows: a collapsible section holding the
 * answer as a tree that opens one level at a time (so a large answer starts as a handful of
 * lines), and a search over every name, path and value in it. Tapping a value calls [onPick];
 * a list's "use as list" (or, in [PickMode.LIST], tapping the list) calls [onUseList].
 */
@Composable
internal fun ApiFieldPicker(
    root: Any?,
    mode: PickMode,
    onPick: (ApiJson.Node) -> Unit,
    onUseList: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** For rows: the list whose items the tree shows, named in the heading. */
    listPath: String = "",
    startOpen: Boolean = true,
) {
    val colors = Metro.colors
    var open by remember { mutableStateOf(startOpen) }
    var query by remember(root) { mutableStateOf("") }
    val expanded = remember(root) { mutableStateMapOf<String, Boolean>() }
    val shownMore = remember(root) { mutableStateMapOf<String, Boolean>() }
    val total = remember(root) { ApiJson.count(root) }

    Column(modifier.fillMaxWidth()) {
        val title = when (mode) {
            PickMode.FIELDS -> "FIELDS"
            PickMode.ROW -> "FIELDS OF EACH ITEM"
            PickMode.LIST -> "LISTS"
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(onClickLabel = if (open) "collapse fields" else "expand fields", role = Role.Button) { open = !open }
                .semantics {
                    heading()
                    stateDescription = if (open) "expanded" else "collapsed"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetroText(if (open) "▾" else "▸", MetroType.body, color = colors.subtle, modifier = Modifier.width(20.dp))
            MetroText(title, MetroType.appName, color = colors.subtle, modifier = Modifier.weight(1f))
            if (root != null) MetroText(if (total > ApiJson.MAX_VISITS) "lots" else "$total in the answer", MetroType.caption, color = colors.subtle)
        }
        if (!open) return@Column
        if (root == null) {
            MetroText(
                if (mode == PickMode.ROW) "Pick a list first: the fields of its items show here." else "Fetch the address to see what it answers.",
                MetroType.caption, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp),
            )
            return@Column
        }
        MetroText(
            when (mode) {
                PickMode.FIELDS -> "Tap a value to add it. Open { } and [ ] to look inside; a list can become the tile's rows."
                PickMode.ROW -> "Each row reads one item of ${listPath.ifBlank { "the list" }}. Tap a value to add it."
                PickMode.LIST -> "Tap the list to show as rows."
            },
            MetroType.caption, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp),
        )
        SearchBox(query, { query = it.take(80) })

        if (query.isNotBlank()) {
            val found = remember(root, query, mode) { ApiJson.search(root, query, 60) }
            val nodes = found.nodes.filter { mode != PickMode.LIST || it.kind == ApiJson.Kind.LIST }
            if (nodes.isEmpty()) {
                MetroText("Nothing called or holding \"${query.trim()}\".", MetroType.caption, color = colors.subtle, modifier = Modifier.padding(vertical = 10.dp))
            }
            nodes.forEach { node ->
                NodeLine(
                    node, depth = 0, showPath = true, isOpen = false, mode = mode,
                    onClick = {
                        if (node.expandable && !(mode == PickMode.LIST && node.kind == ApiJson.Kind.LIST)) {
                            // Show it in the tree, opened.
                            ApiJson.ancestors(node.path).forEach { expanded[it] = true }
                            query = ""
                        } else {
                            tap(node, mode, onPick, onUseList)
                        }
                    },
                    onUseList = onUseList,
                )
            }
            if (!found.complete) MetroText("Showing the first matches: type more to narrow it.", MetroType.caption, color = colors.subtle, modifier = Modifier.padding(top = 6.dp))
        } else {
            val lines = remember(root, expanded.toMap(), shownMore.toMap()) { treeLines(root, expanded, shownMore) }
            lines.forEach { line ->
                when (line) {
                    is TreeLine.Entry -> NodeLine(
                        line.node, line.depth, showPath = false, isOpen = expanded[line.node.path] == true, mode = mode,
                        onClick = {
                            if (line.node.expandable && !(mode == PickMode.LIST && line.node.kind == ApiJson.Kind.LIST)) {
                                expanded[line.node.path] = expanded[line.node.path] != true
                            } else {
                                tap(line.node, mode, onPick, onUseList)
                            }
                        },
                        onUseList = onUseList,
                        onToggle = { expanded[line.node.path] = expanded[line.node.path] != true },
                    )
                    is TreeLine.Items -> MetroText(
                        "and ${line.hidden} more items like these",
                        MetroType.caption, color = colors.subtle,
                        modifier = Modifier.padding(start = (line.depth * 16 + 20).dp, top = 4.dp, bottom = 8.dp),
                    )
                    is TreeLine.More -> MetroText(
                        "show ${line.hidden} more",
                        MetroType.body, color = colors.accent,
                        modifier = Modifier
                            .padding(start = (line.depth * 16 + 20).dp)
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button) { shownMore[line.parent] = true }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private fun tap(node: ApiJson.Node, mode: PickMode, onPick: (ApiJson.Node) -> Unit, onUseList: (String) -> Unit) {
    when {
        node.kind == ApiJson.Kind.LIST && mode == PickMode.LIST -> onUseList(node.path)
        mode == PickMode.LIST -> Unit
        else -> onPick(node)
    }
}

/** The tree's lines: the top level, and inside every entry that is open. */
private fun treeLines(root: Any?, expanded: Map<String, Boolean>, shownMore: Map<String, Boolean>): List<TreeLine> {
    val out = ArrayList<TreeLine>()
    fun add(value: Any?, path: String, depth: Int) {
        val children = ApiJson.children(value, path)
        val limit = if (shownMore[path] == true) children.size else FIELDS_SHOWN
        children.take(limit).forEach { child ->
            out += TreeLine.Entry(child, depth)
            if (child.expandable && expanded[child.path] == true && depth < 12) add(ApiJson.valueAt(root, child.path), child.path, depth + 1)
        }
        if (children.size > limit) out += TreeLine.More(path, children.size - limit, depth)
        // A list shows its first items only: search reaches the rest.
        val items = (value as? org.json.JSONArray)?.length() ?: 0
        if (items > ApiJson.MAX_ITEMS_SHOWN) out += TreeLine.Items(items - ApiJson.MAX_ITEMS_SHOWN, depth)
    }
    if (root is org.json.JSONObject || root is org.json.JSONArray) add(root, "", 0) else out += TreeLine.Entry(ApiJson.node(root, "@", "@"), 0)
    return out
}

@Composable
private fun NodeLine(
    node: ApiJson.Node,
    depth: Int,
    showPath: Boolean,
    isOpen: Boolean,
    mode: PickMode,
    onClick: () -> Unit,
    onUseList: (String) -> Unit,
    onToggle: () -> Unit = onClick,
) {
    val colors = Metro.colors
    val isList = node.kind == ApiJson.Kind.LIST
    val pickable = when (mode) {
        PickMode.LIST -> isList || node.expandable
        else -> true
    }
    val describe = when (node.kind) {
        ApiJson.Kind.OBJECT -> "{ ${node.size} }"
        ApiJson.Kind.LIST -> "[ ${node.size} ]"
        else -> node.sample
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clickable(enabled = pickable, role = Role.Button, onClickLabel = actionLabel(node, mode, isOpen)) { onClick() }
            .semantics { contentDescription = "${node.path}, $describe" }
            .padding(start = (depth * 16).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val chevron = when {
            !node.expandable -> ""
            isList && mode == PickMode.LIST -> ""
            isOpen -> "▾"
            else -> "▸"
        }
        MetroText(chevron, MetroType.body, color = colors.subtle, modifier = Modifier.width(20.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            MetroText(
                if (showPath) node.path else node.key,
                MetroType.body.copy(fontFamily = FontFamily.Monospace),
                color = if (pickable) colors.foreground else colors.subtle,
                maxLines = 1,
            )
            if (showPath && node.sample.isNotEmpty()) MetroText(node.sample, MetroType.caption, color = colors.subtle, maxLines = 1)
        }
        if (!showPath || node.sample.isEmpty()) {
            MetroText(describe, MetroType.caption, color = colors.subtle, maxLines = 1, modifier = Modifier.padding(start = 8.dp).weight(0.8f, fill = false))
        }
        if (isList && mode == PickMode.FIELDS && node.size > 0) {
            MetroText(
                "use as list",
                MetroType.caption, color = colors.accent,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClickLabel = "show ${node.path} as rows") { onUseList(node.path) }
                    .padding(horizontal = 4.dp, vertical = 12.dp),
            )
        }
    }
}

private fun actionLabel(node: ApiJson.Node, mode: PickMode, isOpen: Boolean): String = when {
    node.kind == ApiJson.Kind.LIST && mode == PickMode.LIST -> "show ${node.path} as rows"
    node.expandable -> if (isOpen) "close ${node.key}" else "open ${node.key}"
    else -> "add ${node.path}"
}

@Composable
private fun SearchBox(value: String, onValueChange: (String) -> Unit) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MetroType.listItem.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "search fields" },
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(colors.control.copy(alpha = 0.6f))
                    .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) MetroText("search names and values", MetroType.listItem, color = colors.subtle, maxLines = 1)
                field()
            }
        },
    )
}
