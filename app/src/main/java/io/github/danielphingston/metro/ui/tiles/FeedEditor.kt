package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import io.github.danielphingston.metro.live.feed.FeedAddress
import io.github.danielphingston.metro.live.feed.FeedAddress.RedditFeed
import io.github.danielphingston.metro.live.feed.FeedAddress.RedditSort
import io.github.danielphingston.metro.live.feed.FeedCheck
import io.github.danielphingston.metro.live.feed.FeedProblem
import io.github.danielphingston.metro.live.feed.FeedStore
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the editor says under its fields. [addAnyway]: the problem may pass, so the user may go ahead. */
internal sealed interface FeedEditorStatus {
    data object None : FeedEditorStatus
    data class Checking(val text: String) : FeedEditorStatus
    data class Error(val text: String, val addAnyway: (() -> Unit)? = null) : FeedEditorStatus
}

/** The two kinds of feed tile the editor makes. */
internal enum class FeedKind { REDDIT, RSS }

/**
 * Sets up a feed tile, over a dimmed start screen: either a list of subreddits (added one or
 * several at a time, each checked as it is added) with a sort, or any RSS or Atom address,
 * downloaded and read before it is accepted. Problems are said under the field, with "add
 * anyway" when they may pass (no connection, an empty feed). [isTaken] refuses a feed another
 * tile already shows. Back or a tap outside cancels.
 */
@Composable
internal fun FeedEditor(
    initial: String?,
    isTaken: (String) -> Boolean,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val start = remember { initial?.let(FeedAddress::reddit) }
    var kind by remember { mutableStateOf(if (initial == null || start != null) FeedKind.REDDIT else FeedKind.RSS) }
    val subs = remember { mutableStateListOf<String>().apply { start?.subreddits?.let(::addAll) } }
    var sort by remember { mutableStateOf(start?.sort ?: RedditSort.HOT) }
    var subInput by remember { mutableStateOf(TextFieldValue("")) }
    val rssStart = if (start == null) initial?.let(FeedAddress::shown).orEmpty() else ""
    var rssInput by remember { mutableStateOf(TextFieldValue(rssStart, TextRange(0, rssStart.length))) }
    var status by remember { mutableStateOf<FeedEditorStatus>(FeedEditorStatus.None) }
    var job by remember { mutableStateOf<Job?>(null) }
    // Read when a tap happens, not when the screen was last drawn: a finished check doesn't redraw it.
    fun busy() = job?.isActive == true

    fun taken(url: String): Boolean {
        if (!isTaken(url)) return false
        status = FeedEditorStatus.Error("another tile already shows this feed")
        return true
    }

    /** Checks and adds what the subreddit field holds; false when something couldn't be added. */
    suspend fun addTyped(): Boolean {
        val pieces = FeedAddress.splitSubreddits(subInput.text)
        if (pieces.isEmpty()) return true
        val names = ArrayList<String>()
        for (piece in pieces) {
            val name = FeedAddress.subreddit(piece)
            if (name == null) {
                status = FeedEditorStatus.Error("“$piece” isn't a subreddit name")
                return false
            }
            if (subs.none { it.equals(name, ignoreCase = true) } && names.none { it.equals(name, ignoreCase = true) }) names += name
        }
        if (subs.size + names.size > FeedAddress.MAX_SUBREDDITS) {
            status = FeedEditorStatus.Error("a tile holds up to ${FeedAddress.MAX_SUBREDDITS} subreddits")
            return false
        }
        for ((i, name) in names.withIndex()) {
            status = FeedEditorStatus.Checking("checking r/$name…")
            val result = FeedStore.check(context, FeedAddress.redditUrl(RedditFeed(listOf(name))), keep = false)
            if (result is FeedCheck.Failed) {
                // The name that failed, and those not checked yet, stay in the field to fix or retry.
                val left = names.drop(i).joinToString(" ")
                subInput = TextFieldValue(left, TextRange(left.length))
                status = FeedEditorStatus.Error(
                    result.problem.subredditMessage(name),
                    if (result.problem.retryable) {
                        {
                            subs += name
                            val rest = names.drop(i + 1).joinToString(" ")
                            subInput = TextFieldValue(rest, TextRange(rest.length))
                            status = FeedEditorStatus.None
                        }
                    } else {
                        null
                    },
                )
                return false
            }
            subs += name
        }
        subInput = TextFieldValue("")
        status = FeedEditorStatus.None
        return true
    }

    fun launchCheck(block: suspend () -> Unit) {
        if (busy()) return
        job = scope.launch { block() }
    }

    fun done() = launchCheck check@{
        when (kind) {
            FeedKind.REDDIT -> {
                if (!addTyped()) return@check
                if (subs.isEmpty()) {
                    status = FeedEditorStatus.Error("add at least one subreddit")
                    return@check
                }
                val url = FeedAddress.redditUrl(RedditFeed(subs.toList(), sort))
                if (!taken(url)) onDone(url)
            }
            FeedKind.RSS -> {
                val url = FeedAddress.normalize(rssInput.text)
                if (url == null) {
                    status = FeedEditorStatus.Error(FeedProblem.BAD_ADDRESS.message())
                    return@check
                }
                if (taken(url)) return@check
                if (url == initial) {
                    onDone(url)
                    return@check
                }
                status = FeedEditorStatus.Checking("checking the feed…")
                when (val result = FeedStore.check(context, url)) {
                    is FeedCheck.Ok -> if (!taken(result.url)) onDone(result.url)
                    is FeedCheck.Failed -> status = FeedEditorStatus.Error(
                        result.message,
                        if (result.problem.retryable) {
                            { onDone(url) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("reddit", kind == FeedKind.REDDIT, {
                    kind = FeedKind.REDDIT
                    status = FeedEditorStatus.None
                })
                MetroChoice("rss or atom", kind == FeedKind.RSS, {
                    kind = FeedKind.RSS
                    status = FeedEditorStatus.None
                })
            }
            Spacer(Modifier.height(20.dp))
            when (kind) {
                FeedKind.REDDIT -> {
                    Caption("SUBREDDITS")
                    subs.forEachIndexed { index, name ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MetroText("r/$name", MetroType.listItem, maxLines = 1, modifier = Modifier.weight(1f))
                            MetroText(
                                "remove",
                                MetroType.body,
                                color = colors.accent,
                                modifier = Modifier
                                    .clickable(onClickLabel = "remove r/$name") {
                                        if (!busy()) {
                                            subs.removeAt(index)
                                            status = FeedEditorStatus.None
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 10.dp),
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (subs.isEmpty()) 0.dp else 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FeedField(
                            value = subInput,
                            onValueChange = {
                                subInput = it
                                if (status is FeedEditorStatus.Error) status = FeedEditorStatus.None
                            },
                            placeholder = if (subs.isEmpty()) "android, pixel…" else "add another",
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Go,
                            onAction = { launchCheck { addTyped() } },
                            focusFirst = initial == null,
                            modifier = Modifier.weight(1f),
                        )
                        MetroButton("add", { launchCheck { addTyped() } })
                    }
                    Spacer(Modifier.height(20.dp))
                    Caption("SHOW")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RedditSort.entries.forEach { s -> MetroChoice(s.label, sort == s, { sort = s }) }
                    }
                }
                FeedKind.RSS -> {
                    Caption("ADDRESS")
                    FeedField(
                        value = rssInput,
                        onValueChange = {
                            rssInput = it
                            if (status is FeedEditorStatus.Error) status = FeedEditorStatus.None
                        },
                        placeholder = "example.com/feed",
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                        onAction = ::done,
                        focusFirst = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MetroText(
                        "an RSS or Atom address, or a site's page if it names its feed",
                        MetroType.caption,
                        color = colors.subtle,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            StatusLine(status)
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
    keyboardType: KeyboardType,
    imeAction: ImeAction,
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
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
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
