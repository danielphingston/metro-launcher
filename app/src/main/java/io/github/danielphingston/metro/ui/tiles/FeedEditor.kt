package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedAddress.LemmySort
import io.github.danielphingston.metro.live.feed.FeedCheck
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor says under its fields. [addAnyway]: the problem may pass, so the user may go ahead. */
internal sealed interface FeedEditorStatus {
    data object None : FeedEditorStatus
    data class Checking(val text: String) : FeedEditorStatus
    data class Error(val text: String, val addAnyway: (() -> Unit)? = null) : FeedEditorStatus
}

/**
 * Sets up a feed tile, over a dimmed start screen: a list of feeds, Lemmy communities
 * ("!linux@lemmy.ml" or a community link) and RSS or Atom addresses alike, each downloaded and
 * read as it is added; how communities are sorted; and which app opens the posts. Problems are
 * said under the field, with "add anyway" when they may pass (no connection, an empty feed).
 * [isTaken] refuses the same feeds another tile already shows. Back or a tap outside cancels.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedEditor(
    initial: TileContent.Feed?,
    isTaken: (List<String>) -> Boolean,
    onDone: (TileContent.Feed) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val urls = remember { mutableStateListOf<String>().apply { initial?.urls?.let(::addAll) } }
    var sort by remember { mutableStateOf(initial?.urls?.firstNotNullOfOrNull { FeedAddress.lemmy(it)?.second } ?: LemmySort.HOT) }
    var openWith by remember { mutableStateOf(initial?.openWith) }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var status by remember { mutableStateOf<FeedEditorStatus>(FeedEditorStatus.None) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Read when a tap happens, not when the screen was last drawn: a finished check doesn't redraw it.
    fun busy() = job?.isActive == true

    // The apps that open these feeds' posts; looked up again as feeds come and go.
    var apps by remember { mutableStateOf<List<FeedTiles.LinkApp>>(emptyList()) }
    val hosts = urls.map { FeedAddress.samplePostLink(it) }.distinct()
    LaunchedEffect(hosts) {
        apps = withContext(Dispatchers.Default) { FeedTiles.linkApps(context, urls.toList()) }
    }

    fun setInput(text: String) {
        input = TextFieldValue(text, TextRange(text.length))
    }

    /** Checks and adds what the field holds; false when something couldn't be added. */
    suspend fun addTyped(): Boolean {
        val pieces = FeedAddress.split(input.text)
        for ((i, piece) in pieces.withIndex()) {
            // The entry that fails, and those after it, stay in the field to fix or retry.
            val left = pieces.drop(i).joinToString(" ")
            val rest = pieces.drop(i + 1).joinToString(" ")
            val entry = FeedAddress.parse(piece)
            val (url, community) = when (entry) {
                is FeedAddress.Entry.Invalid -> {
                    setInput(left)
                    status = FeedEditorStatus.Error(entry.message)
                    return false
                }
                is FeedAddress.Entry.Lemmy -> FeedAddress.lemmyUrl(entry.community, sort) to entry.community
                is FeedAddress.Entry.Address -> entry.url to null
            }
            if (urls.any { FeedAddress.key(it) == FeedAddress.key(url) }) continue
            if (urls.size >= FeedAddress.MAX_FEEDS) {
                setInput(left)
                status = FeedEditorStatus.Error("a tile holds up to ${FeedAddress.MAX_FEEDS} feeds")
                return false
            }
            status = FeedEditorStatus.Checking("checking ${community ?: FeedAddress.label(url)}…")
            when (val result = FeedStore.check(context, url)) {
                // A web page may have led to its feed: that one is added.
                is FeedCheck.Ok -> if (urls.none { FeedAddress.key(it) == FeedAddress.key(result.url) }) urls += result.url
                is FeedCheck.Failed -> {
                    setInput(left)
                    status = FeedEditorStatus.Error(
                        community?.let(result.problem::communityMessage) ?: result.message,
                        if (result.problem.retryable) {
                            {
                                urls += url
                                setInput(rest)
                                status = FeedEditorStatus.None
                            }
                        } else {
                            null
                        },
                    )
                    return false
                }
            }
        }
        setInput("")
        status = FeedEditorStatus.None
        return true
    }

    fun launchCheck(block: suspend () -> Unit) {
        if (busy()) return
        job = scope.launch { block() }
    }

    fun done() = launchCheck check@{
        if (!addTyped()) return@check
        if (urls.isEmpty()) {
            status = FeedEditorStatus.Error("add at least one feed")
            return@check
        }
        val list = urls.toList()
        if (isTaken(list)) {
            status = FeedEditorStatus.Error("another tile already shows these feeds")
            return@check
        }
        onDone(TileContent.Feed(list, openWith?.takeIf { pkg -> apps.any { it.packageName == pkg } }))
    }

    val colors = Metro.colors
    MetroFlyout(onDismiss = onCancel, paneTitle = "feed") {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .background(colors.chrome)
                // Keeps taps on the panel from dismissing it.
                .clickable(enabled = false, onClick = {})
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        ) {
            MetroText("feed", MetroType.pageTitle, modifier = Modifier.padding(bottom = 12.dp))
            Caption("FEEDS")
            urls.forEachIndexed { index, url ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    MetroText(FeedAddress.shown(url), MetroType.listItem, maxLines = 1, modifier = Modifier.weight(1f))
                    MetroText(
                        "remove",
                        MetroType.body,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable(onClickLabel = "remove ${FeedAddress.shown(url)}") {
                                if (!busy()) {
                                    urls.removeAt(index)
                                    status = FeedEditorStatus.None
                                }
                            }
                            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = if (urls.isEmpty()) 0.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FeedField(
                    value = input,
                    onValueChange = {
                        input = it
                        if (status is FeedEditorStatus.Error) status = FeedEditorStatus.None
                    },
                    placeholder = if (urls.isEmpty()) "!linux@lemmy.ml" else "add another",
                    onAction = { launchCheck { addTyped() } },
                    focusFirst = initial == null,
                    modifier = Modifier.weight(1f),
                )
                MetroButton("add", { launchCheck { addTyped() } })
            }
            MetroText(
                "a Lemmy community, or any RSS or Atom address (a site's page works if it names its feed)",
                MetroType.caption,
                color = colors.subtle,
                modifier = Modifier.padding(top = 8.dp),
            )
            StatusLine(status)
            if (urls.any { FeedAddress.lemmy(it) != null }) {
                Spacer(Modifier.height(20.dp))
                Caption("COMMUNITIES SHOW")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LemmySort.entries.forEach { s ->
                        MetroChoice(s.label, sort == s, {
                            sort = s
                            for (i in urls.indices) urls[i] = FeedAddress.withSort(urls[i], s)
                        })
                    }
                }
            }
            if (apps.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Caption("OPEN POSTS IN")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetroChoice("browser or default", openWith == null || apps.none { it.packageName == openWith }, { openWith = null })
                    apps.forEach { app -> MetroChoice(app.label, openWith == app.packageName, { openWith = app.packageName }) }
                }
            }
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroButton(if (initial == null) "add tile" else "done", ::done)
                MetroButton("cancel", onCancel)
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    MetroText(text, MetroType.appName, color = Metro.colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun StatusLine(status: FeedEditorStatus) {
    val colors = Metro.colors
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (status) {
        FeedEditorStatus.None -> Unit
        is FeedEditorStatus.Checking -> MetroText(status.text, MetroType.body, color = colors.subtle, modifier = live.padding(top = 12.dp))
        is FeedEditorStatus.Error -> Column(live.padding(top = 12.dp)) {
            MetroText(status.text, MetroType.body, color = colors.accent)
            status.addAnyway?.let { anyway ->
                Spacer(Modifier.height(8.dp))
                MetroButton("add anyway", anyway)
            }
        }
    }
}

/** A one-line field styled like the app list's search box. */
@Composable
private fun FeedField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onAction: () -> Unit,
    focusFirst: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    if (focusFirst) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MetroType.listItem.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onAction() }),
        modifier = modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused },
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
                if (value.text.isEmpty()) MetroText(placeholder, MetroType.listItem, color = colors.subtle, maxLines = 1)
                field()
            }
        },
    )
}
