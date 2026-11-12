package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LayoutNames
import io.github.danielphingston.metro.data.LayoutSet
import io.github.danielphingston.metro.data.SavedLayout
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.SearchField
import io.github.danielphingston.metro.ui.components.SearchFieldStyle
import io.github.danielphingston.metro.ui.components.metroTilt
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.launch

/** What the layouts page is asking for: a name for a new layout or a renamed one, or a confirmed delete. */
private sealed interface LayoutEdit {
    data object SaveNew : LayoutEdit
    data class Rename(val layout: SavedLayout) : LayoutEdit
    data class Delete(val layout: SavedLayout) : LayoutEdit
}

/**
 * The saved start layouts: the one on start marked, each with switch, rename, duplicate and
 * delete (not the one on start), and "save current as new layout". Before the first save the
 * current start is shown as the one layout there is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LayoutsPage() {
    val app = LocalContext.current.applicationContext as? MetroApplication ?: return
    val repository = app.layouts
    val set by repository.layouts.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = Metro.colors
    var open by remember { mutableStateOf<Long?>(null) }
    var edit by remember { mutableStateOf<LayoutEdit?>(null) }

    Spacer(Modifier.height(12.dp))
    MetroText(
        "Keep more than one start screen, such as work and weekend, each with its own tiles, and switch between them here, " +
            "from a switch tile (the + under the tiles) or by searching for “layout” and its name.",
        MetroType.caption,
        color = colors.subtle,
    )
    Spacer(Modifier.height(16.dp))

    val current = set
    val rows = current?.layouts ?: listOf(SavedLayout(0, LayoutNames.FIRST, ""))
    rows.forEach { layout ->
        val active = current == null || layout.id == current.active
        Column(
            Modifier
                .fillMaxWidth()
                .metroTilt()
                .clickable(role = Role.Button, enabled = current != null, onClickLabel = "show actions") {
                    open = if (open == layout.id) null else layout.id
                    edit = null
                }
                .semantics { selected = active }
                .padding(vertical = 8.dp),
        ) {
            MetroText(layout.name, MetroType.sectionTitle, maxLines = 1)
            MetroText(if (active) "on start now" else "tap for actions", MetroType.caption, color = if (active) colors.accent else colors.subtle)
        }
        if (current != null && open == layout.id) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!active) MetroButton("switch to", onClick = { scope.launch { repository.switchTo(layout.id) } })
                MetroButton("rename", onClick = { edit = LayoutEdit.Rename(layout) })
                MetroButton("duplicate", onClick = { scope.launch { repository.duplicate(layout.id) } })
                if (!active) MetroButton("delete", onClick = { edit = LayoutEdit.Delete(layout) })
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    when (val e = edit) {
        null -> MetroButton("save current as new layout", onClick = { edit = LayoutEdit.SaveNew })
        is LayoutEdit.Delete -> DeleteConfirm(
            e.layout,
            onDelete = {
                scope.launch { repository.delete(e.layout.id) }
                edit = null
                open = null
            },
            onCancel = { edit = null },
        )
        else -> NameEditor(
            initial = (e as? LayoutEdit.Rename)?.layout?.name.orEmpty(),
            title = if (e is LayoutEdit.Rename) "rename layout" else "new layout",
            note = if (current == null) "Your start screen as it is now is kept as “${LayoutNames.FIRST}”, and the new layout starts as a copy of it." else "The new layout starts as a copy of the start screen as it is now.",
            set = current,
            exceptId = (e as? LayoutEdit.Rename)?.layout?.id,
            onSave = { name ->
                scope.launch {
                    val problem = if (e is LayoutEdit.Rename) repository.rename(e.layout.id, name) else repository.saveCurrentAs(name)
                    if (problem == null) edit = null
                }
            },
            onCancel = { edit = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameEditor(
    initial: String,
    title: String,
    note: String,
    set: LayoutSet?,
    exceptId: Long?,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Metro.colors
    var text by remember(initial) { mutableStateOf(initial) }
    val problem = LayoutNames.problem(LayoutNames.clean(text), set, exceptId)
    MetroText(title, MetroType.sectionTitle)
    Spacer(Modifier.height(4.dp))
    if (exceptId == null) MetroText(note, MetroType.caption, color = colors.subtle)
    Spacer(Modifier.height(12.dp))
    SearchField(text, { text = it.take(LayoutNames.MAX) }, placeholder = "name, such as work", style = SearchFieldStyle.OUTLINED)
    // Nothing nags before the first letter; an empty field only keeps the button off.
    if (text.isNotBlank() && problem != null) {
        Spacer(Modifier.height(4.dp))
        MetroText(problem, MetroType.caption, color = colors.accent)
    }
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroButton("save", onClick = { if (problem == null) onSave(text) }, modifier = Modifier.alpha(if (problem == null) 1f else 0.4f))
        MetroButton("cancel", onClick = onCancel)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeleteConfirm(layout: SavedLayout, onDelete: () -> Unit, onCancel: () -> Unit) {
    MetroText("delete ${layout.name}?", MetroType.sectionTitle)
    Spacer(Modifier.height(4.dp))
    MetroText("Its tiles go with it. Your other layouts stay as they are.", MetroType.caption, color = Metro.colors.subtle)
    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetroButton("delete", onClick = onDelete)
        MetroButton("cancel", onClick = onCancel)
    }
}
