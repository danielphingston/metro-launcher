package io.github.danielphingston.metro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.MetroApplication
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.search.SearchHelp
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType

/** What every search understands, Metro Plus or not. */
private val freeExamples = listOf(
    SearchHelp.Example("yt", "an app by its initials: YouTube"),
    SearchHelp.Example("camra", "small typos still find the app"),
    SearchHelp.Example("github.com", "opens the address"),
    SearchHelp.Example("anything else", "searches the web"),
)

@Composable
internal fun SearchHelpPageHost(settings: LauncherSettings, onOpenMetroPlus: () -> Unit) {
    val metro = LocalContext.current.applicationContext as MetroApplication
    val unlocked by metro.purchases.quickTilesUnlocked.collectAsState()
    SearchHelpPage(settings, unlocked, onOpenMetroPlus)
}

/**
 * The "what can I type?" page: every phrase smart search understands, grouped by feature, with
 * what to type in the accent colour and what it does under it. Anyone can read it; while Metro
 * Plus is locked a note on top says these need it, and a feature that is off says so by its title.
 */
@Composable
internal fun SearchHelpPage(settings: LauncherSettings, unlocked: Boolean, onUnlock: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    if (!unlocked) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
            LockGlyph(Metro.colors.subtle, Modifier.padding(top = 4.dp).size(width = 20.dp, height = 22.dp))
            Column(Modifier.weight(1f)) {
                MetroText("a Metro Plus feature", MetroType.body)
                MetroText(
                    "These work once Metro Plus is unlocked, a one-time purchase. Until then search finds apps and the web.",
                    MetroType.caption,
                    color = Metro.colors.subtle,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        MetroButton("unlock with Metro Plus", onClick = onUnlock)
        Spacer(Modifier.height(8.dp))
    } else {
        MetroText(
            "Type these in search; what to type is in colour. The answer or action shows as you type, tap it to use it.",
            MetroType.caption,
            color = Metro.colors.subtle,
        )
    }
    SearchHelp.groups.forEach { group ->
        GroupTitle(group.title, if (unlocked && !settings.smartOn(group.feature)) "off" else null)
        group.examples.forEach { ExampleRow(it) }
    }
    GroupTitle("free for everyone", null)
    freeExamples.forEach { ExampleRow(it) }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun GroupTitle(title: String, note: String?) {
    Row(
        Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetroText(title, MetroType.sectionTitle)
        note?.let { MetroText("$it · switch it on in smart search", MetroType.caption, color = Metro.colors.subtle) }
    }
}

/** A phrase in the accent colour, what it does in the subtle one under it. */
@Composable
private fun ExampleRow(example: SearchHelp.Example) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) { }) {
        MetroText(example.phrase, MetroType.body, color = Metro.colors.accent)
        MetroText(example.note, MetroType.caption, color = Metro.colors.subtle)
    }
}
