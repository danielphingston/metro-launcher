package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiTileJson
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.api.ApiCheck
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiSecrets
import io.github.danielphingston.metro.live.api.ApiStore
import io.github.danielphingston.metro.live.api.BuiltInFormulas
import io.github.danielphingston.metro.live.api.CachedApi
import io.github.danielphingston.metro.ui.components.LabeledSlider
import io.github.danielphingston.metro.ui.components.MetroButton
import io.github.danielphingston.metro.ui.components.MetroChoice
import io.github.danielphingston.metro.ui.components.MetroFlyout
import io.github.danielphingston.metro.ui.components.MetroText
import io.github.danielphingston.metro.ui.components.MetroToggle
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/** What a template in the editor fills: a slot of the view, or a part of its list. */
internal enum class ApiTarget(val label: String, val slot: ApiSlot? = null) {
    TITLE("title", ApiSlot.TITLE),
    VALUE("big value", ApiSlot.VALUE),
    LINE1("line 1", ApiSlot.LINE1),
    LINE2("line 2", ApiSlot.LINE2),
    IMAGE("picture", ApiSlot.IMAGE),
    LINK("link", ApiSlot.LINK),
    LIST("list"),
    ROW_PRIMARY("row text"),
    ROW_SECONDARY("row detail"),
    ROW_IMAGE("row picture"),
    ;

    val isRow: Boolean get() = this == ROW_PRIMARY || this == ROW_SECONDARY || this == ROW_IMAGE

    /** The list's path is a plain path, not a template: picking a field sets it instead of adding to it. */
    val isPath: Boolean get() = this == LIST
}

/** One header row in the editor: [value] is shown hidden, like a password. */
internal class HeaderRow(name: String, value: String) {
    var name by mutableStateOf(name)
    var value by mutableStateOf(value)
}

/** The editor's two views. */
internal enum class ApiEditorTab { TILE, EXPANDED }

/** A view's templates, by target, as the editor holds them. */
internal fun templatesOf(view: ApiView): Map<ApiTarget, String> = buildMap {
    ApiTarget.entries.forEach { t -> t.slot?.let { s -> view.slots[s]?.let { put(t, it) } } }
    view.list?.let {
        put(ApiTarget.LIST, it.path)
        put(ApiTarget.ROW_PRIMARY, it.primary)
        put(ApiTarget.ROW_SECONDARY, it.secondary)
        put(ApiTarget.ROW_IMAGE, it.image)
    }
}

/** The view [templates] make, reading [url]. A list needs a path and a row text. */
internal fun viewOf(url: String, templates: Map<ApiTarget, String>): ApiView {
    val slots = ApiTarget.entries.mapNotNull { t -> t.slot?.let { s -> templates[t]?.trim()?.takeIf { it.isNotEmpty() }?.let { s to it } } }.toMap()
    val path = templates[ApiTarget.LIST]?.trim().orEmpty()
    val primary = templates[ApiTarget.ROW_PRIMARY]?.trim().orEmpty()
    val list = if (path.isNotEmpty() && primary.isNotEmpty()) {
        ApiList(path, primary, templates[ApiTarget.ROW_SECONDARY]?.trim().orEmpty(), templates[ApiTarget.ROW_IMAGE]?.trim().orEmpty())
    } else {
        null
    }
    return ApiView(url, slots, list)
}

/** Why [name] can't be saved as it stands, or null. */
internal fun apiTileProblem(
    url: String,
    detailUrl: String,
    tile: Map<ApiTarget, String>,
    detail: Map<ApiTarget, String>,
    headers: List<Pair<String, String>>,
): String? {
    if (ApiStore.address(url) == null) return "the tile's address must be an https address"
    if (detailUrl.isNotBlank() && ApiStore.address(detailUrl) == null) return "the expanded view's address must be an https address"
    val shown = viewOf(url, tile)
    if (shown.slots.keys.none { it != ApiSlot.LINK } && shown.list == null) return "pick at least one field for the tile to show"
    for ((which, templates) in listOf("tile" to tile, "expanded view" to detail)) {
        for ((target, text) in templates) {
            if (target.isPath) continue
            BuiltInFormulas.problem(text)?.let { return "$which, ${target.label}: $it" }
        }
        if (templates[ApiTarget.LIST].isNullOrBlank() != templates[ApiTarget.ROW_PRIMARY].isNullOrBlank()) {
            return "$which: a list needs both its path and a row text"
        }
    }
    val names = headers.map { it.first.trim() }.filter { it.isNotEmpty() }
    names.firstOrNull { !ApiTileJson.validHeader(it) }?.let { return "$it can't be used as a header name" }
    if (names.map { it.lowercase() }.toSet().size != names.size) return "each header can be set once"
    headers.firstOrNull { it.first.isNotBlank() && it.second.isEmpty() }?.let { return "${it.first} needs a value" }
    return null
}

/**
 * Sets up an API tile: the address and any headers (an API key), a fetch of the real answer, then
 * what to show, by picking fields from that answer into the tile's slots and list (templates with
 * formulas: see [BuiltInFormulas]), with a live preview. A second tab sets up the expanded view a
 * tap opens: its own address if wanted, and live updates, off by default, with their interval.
 * Header values are kept encrypted on the phone ([ApiSecrets]), never in the layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ApiEditor(
    initial: TileContent.Api?,
    onDone: (TileContent.Api) -> Unit,
    onCancel: () -> Unit,
    /** The answer to show while setting up, for previews and tests; null fetches it. */
    sample: Any? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val created = remember { initial?.created?.takeIf { it != 0L } ?: System.currentTimeMillis() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.tile?.url.orEmpty()) }
    var detailUrl by remember { mutableStateOf(initial?.detail?.url.orEmpty()) }
    var separateDetail by remember { mutableStateOf(!initial?.detail?.url.isNullOrEmpty()) }
    val headers = remember { mutableStateListOf<HeaderRow>().apply { initial?.headers?.forEach { add(HeaderRow(it, "")) } } }
    val tileTemplates = remember { mutableStateMapOf<ApiTarget, String>().apply { initial?.let { putAll(templatesOf(it.tile)) } } }
    val detailTemplates = remember { mutableStateMapOf<ApiTarget, String>().apply { initial?.let { putAll(templatesOf(it.detail)) } } }
    var refresh by remember { mutableStateOf(initial?.refreshMinutes ?: 15) }
    var liveOn by remember { mutableStateOf((initial?.liveSeconds ?: 0) > 0) }
    var liveSeconds by remember { mutableStateOf(initial?.liveSeconds?.takeIf { it > 0 } ?: 15) }
    var wifiOnly by remember { mutableStateOf(initial?.wifiOnly ?: false) }
    var tab by remember { mutableStateOf(ApiEditorTab.TILE) }
    var target by remember { mutableStateOf(ApiTarget.VALUE) }
    var status by remember { mutableStateOf<FeedEditorStatus>(FeedEditorStatus.None) }
    var tileRoot by remember { mutableStateOf(sample) }
    var detailRoot by remember { mutableStateOf<Any?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saveProblem by remember { mutableStateOf<String?>(null) }

    // Saved header values: read once, so an edited tile keeps its key without showing it.
    LaunchedEffect(Unit) {
        if (initial == null || initial.headers.isEmpty()) return@LaunchedEffect
        val stored = withContext(Dispatchers.IO) { ApiSecrets.of(context).get(created) }
        headers.forEach { row -> stored[row.name]?.let { row.value = it } }
    }

    fun headerMap(): Map<String, String> = headers.mapNotNull { r -> r.name.trim().takeIf { it.isNotEmpty() && r.value.isNotEmpty() }?.let { it to r.value } }.toMap()

    fun fetch(which: ApiEditorTab) {
        if (job?.isActive == true) return
        val address = ApiStore.address(if (which == ApiEditorTab.EXPANDED && separateDetail) detailUrl else url)
        if (address == null) {
            status = FeedEditorStatus.Error(ApiStore.address(url)?.let { "the expanded view's address must be https" } ?: "enter an https address that answers with JSON")
            return
        }
        if (which == ApiEditorTab.TILE) url = address else if (separateDetail) detailUrl = address
        job = scope.launch {
            status = FeedEditorStatus.Checking("fetching ${runCatching { URL(address).host }.getOrDefault(address)}…")
            when (val result = ApiStore.check(context, ApiRequest(address, headerMap()))) {
                is ApiCheck.Ok -> {
                    if (which == ApiEditorTab.TILE) tileRoot = result.root
                    if (which == ApiEditorTab.EXPANDED || !separateDetail) detailRoot = result.root
                    if (name.isBlank()) name = runCatching { URL(address).host.removePrefix("www.").removePrefix("api.") }.getOrDefault("").take(ApiTileJson.MAX_NAME)
                    status = FeedEditorStatus.None
                }
                is ApiCheck.Failed -> status = FeedEditorStatus.Error(result.message)
            }
        }
    }

    // An edited tile shows its last answer at once (if the phone has it), without a download.
    LaunchedEffect(Unit) {
        if (initial == null || sample != null) return@LaunchedEffect
        val values = withContext(Dispatchers.IO) { ApiSecrets.of(context).get(created) }
        val request = ApiRequest(initial.tile.url, initial.headers.mapNotNull { n -> values[n]?.let { n to it } }.toMap())
        ApiStore.load(context, request)
        tileRoot = ApiStore.data.value[request.key]?.root
        if (initial.detail.url.isEmpty()) detailRoot = tileRoot
    }

    fun done() {
        val pairs = headers.map { it.name.trim() to it.value }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
        val problem = apiTileProblem(url, if (separateDetail) detailUrl else "", tileTemplates, detailTemplates, pairs)
        saveProblem = problem
        if (problem != null) return
        val tileUrl = ApiStore.address(url)!!
        val content = TileContent.Api(
            name = name.trim().ifEmpty { runCatching { URL(tileUrl).host }.getOrDefault("api") }.take(ApiTileJson.MAX_NAME),
            tile = viewOf(tileUrl, tileTemplates),
            detail = viewOf(if (separateDetail) ApiStore.address(detailUrl).orEmpty() else "", detailTemplates),
            headers = pairs.map { it.first },
            refreshMinutes = refresh,
            liveSeconds = if (liveOn) liveSeconds else 0,
            wifiOnly = wifiOnly,
            created = created,
        )
        val values = pairs.toMap()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ApiSecrets.of(context).put(created, values) } }
            onDone(content)
        }
    }

    val templates = if (tab == ApiEditorTab.TILE) tileTemplates else detailTemplates
    val root = if (tab == ApiEditorTab.TILE) tileRoot else detailRoot ?: tileRoot
    val colors = Metro.colors
    MetroFlyout(onDismiss = onCancel, paneTitle = "api tile") {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .background(colors.chrome)
                // Keeps taps on the panel from reaching the dismiss scrim, without merging
                // the panel into one semantics node (that hid the inner scroll's real bounds).
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        ) {
            MetroText("api tile", MetroType.pageTitle, modifier = Modifier.padding(bottom = 12.dp))
            Caption("NAME")
            EditorField(name, { name = it.take(ApiTileJson.MAX_NAME) }, "shown on the tile")
            Spacer(Modifier.height(16.dp))
            Caption("ADDRESS")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorField(url, { url = it; status = FeedEditorStatus.None }, "api.example.com/data.json", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                MetroButton("fetch", { fetch(ApiEditorTab.TILE) })
            }
            Hint("https only, answering with JSON (up to 1 MB). Fetch it to pick what to show.")
            Spacer(Modifier.height(16.dp))
            Caption("HEADERS")
            headers.forEachIndexed { i, row ->
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorField(row.name, { row.name = it.take(64) }, "X-Api-Key", Modifier.weight(1f))
                    EditorField(row.value, { row.value = it.take(1024) }, "value", Modifier.weight(1f), secret = true)
                    MetroText(
                        "remove", MetroType.body, color = colors.accent,
                        modifier = Modifier.clickable(onClickLabel = "remove header ${row.name}") { headers.removeAt(i) }.padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
            if (headers.size < ApiTileJson.MAX_HEADERS) MetroButton("add header", { headers += HeaderRow("", "") })
            Hint("For API keys. Values stay encrypted on this phone: never backed up or exported.")
            StatusLine(status)

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroChoice("tile", tab == ApiEditorTab.TILE, { tab = ApiEditorTab.TILE })
                MetroChoice("expanded view", tab == ApiEditorTab.EXPANDED, { tab = ApiEditorTab.EXPANDED })
            }
            Spacer(Modifier.height(12.dp))
            if (tab == ApiEditorTab.EXPANDED) {
                MetroToggle("Same address as the tile", !separateDetail, { separateDetail = !it; detailRoot = null }, description = if (separateDetail) "Reads its own address" else "Reads the tile's address")
                if (separateDetail) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditorField(detailUrl, { detailUrl = it }, "api.example.com/live.json", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                        MetroButton("fetch", { fetch(ApiEditorTab.EXPANDED) })
                    }
                }
                Hint("Empty parts show what the tile shows.")
                Spacer(Modifier.height(12.dp))
            }

            // The preview, from the fetched answer.
            Caption("PREVIEW")
            val cached = root?.let { CachedApi("preview", it, "", System.currentTimeMillis()) }
            val draft = TileContent.Api(name.ifBlank { "api" }, viewOf(url, tileTemplates), viewOf(detailUrl, detailTemplates), created = created)
            if (tab == ApiEditorTab.TILE) {
                PreviewTiles(draft, cached)
            } else {
                Box(Modifier.fillMaxWidth().border(1.dp, colors.control).padding(12.dp)) {
                    ApiDetailBody(draft, cached, System.currentTimeMillis(), live = false, paused = false, maxRows = 5)
                }
            }

            Spacer(Modifier.height(16.dp))
            Caption("SHOW")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ApiTarget.entries.forEach { t ->
                    val filled = !templates[t].isNullOrBlank()
                    MetroChoice(if (filled) "${t.label} ✓" else t.label, target == t, { target = t })
                }
            }
            Spacer(Modifier.height(12.dp))
            TemplateField(
                templates = templates,
                target = target,
                root = root,
            )
            Spacer(Modifier.height(12.dp))
            val listPath = templates[ApiTarget.LIST].orEmpty()
            val pickRoot = remember(root, target.isRow, listPath) {
                if (target.isRow) ApiJson.items(root, listPath, 1).firstOrNull() else root
            }
            ApiFieldPicker(
                root = pickRoot,
                mode = when {
                    target.isPath -> PickMode.LIST
                    target.isRow -> PickMode.ROW
                    else -> PickMode.FIELDS
                },
                listPath = listPath,
                onPick = { node -> templates[target] = withField(templates[target].orEmpty(), node.path, target) },
                onUseList = { path ->
                    useList(templates, path, root)
                    target = ApiTarget.ROW_PRIMARY
                },
            )
            Spacer(Modifier.height(12.dp))

            if (!target.isPath) {
                Spacer(Modifier.height(12.dp))
                Caption("FUNCTIONS: TAP TO ADD")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BuiltInFormulas.FUNCTIONS.forEach { (fn, example) ->
                        MetroChoice(fn, false, {
                            val current = templates[target].orEmpty()
                            templates[target] = if (current.isEmpty()) "{$example}" else "$current {$example}"
                        })
                    }
                }
                Hint("In braces: {round(temp, 1)}°, {countdown(race.start)}, {upper(name)}. Times can be ISO 8601 or seconds or milliseconds since 1970; countdowns tick on the phone without fetching.")
            }

            Spacer(Modifier.height(20.dp))
            Caption("REFRESH THE TILE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 15, 30, 60).forEach { min -> MetroChoice("$min min", refresh == min, { refresh = min }) }
            }
            Hint("Only while Start is on screen with the screen on; never in the background. Battery saver doubles it.")

            Spacer(Modifier.height(20.dp))
            Caption("LIVE UPDATES IN THE EXPANDED VIEW")
            MetroToggle("Live updates", liveOn, { liveOn = it }, description = if (liveOn) "Every $liveSeconds s while it is open" else "Off: updates when opened")
            if (liveOn) {
                LabeledSlider("Every", liveSeconds, ApiTileJson.LIVE_SECONDS, { "$it s" }) { liveSeconds = it }
                MetroToggle("Wi-Fi only", wifiOnly, { wifiOnly = it }, description = if (wifiOnly) "Paused on mobile data" else "On any connection")
            }
            Hint("Polls only while the expanded view is open and the screen is on, and stops when it closes. Battery saver slows it to at least 30 s.")

        }
        // Always in view: the buttons, and why the tile can't be saved yet, next to them.
        Column(Modifier.fillMaxWidth().border(width = 1.dp, color = colors.control).padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)) {
            saveProblem?.let {
                MetroText(it, MetroType.body, color = colors.accent, modifier = Modifier.padding(bottom = 8.dp).semantics { liveRegion = LiveRegionMode.Assertive })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroButton(if (initial == null) "add tile" else "done", ::done)
                MetroButton("cancel", onCancel)
            }
        }
        }
    }
}

/** [current] with the field at [path] added: a picture or link takes the field alone; text gets it appended. */
internal fun withField(current: String, path: String, target: ApiTarget): String {
    val field = "{$path}"
    return when {
        target.isPath -> path
        target == ApiTarget.IMAGE || target == ApiTarget.LINK || target == ApiTarget.ROW_IMAGE -> field
        current.isBlank() -> field
        else -> "$current $field"
    }
}

/**
 * Shows the list at [path] as rows: sets it, and when the rows have no text yet, starts them with
 * the item's first name-like text and first number, so rows show at once (easy to change).
 */
internal fun useList(templates: MutableMap<ApiTarget, String>, path: String, root: Any?) {
    val changed = !templates[ApiTarget.LIST].isNullOrBlank() && templates[ApiTarget.LIST] != path
    templates[ApiTarget.LIST] = path
    if (!changed && !templates[ApiTarget.ROW_PRIMARY].isNullOrBlank()) return
    // Rows set up for another list would read nothing from this one: start them again.
    val (primary, secondary) = ApiJson.suggestRow(ApiJson.items(root, path, 1).firstOrNull())
    templates[ApiTarget.ROW_PRIMARY] = primary?.let { if (it == "@") "{@}" else "{$it}" }.orEmpty()
    templates[ApiTarget.ROW_SECONDARY] = secondary?.let { "{$it}" }.orEmpty()
    if (changed) templates[ApiTarget.ROW_IMAGE] = ""
}

/** The tile at medium and wide size, from the fetched answer. */
@Composable
private fun PreviewTiles(api: TileContent.Api, cached: CachedApi?) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Four columns as on Start, with its 8 dp gaps.
        val cell: Dp = (maxWidth - 24.dp) / 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(cell * 2 + 8.dp).aspectRatio(1f)) {
                    ApiFace(TileSize.MEDIUM, api.name, api.tile, cached, true, rememberTileMetrics(cell), System.currentTimeMillis())
                }
                Box(Modifier.width(cell).aspectRatio(1f)) {
                    ApiFace(TileSize.SMALL, api.name, api.tile, cached, true, rememberTileMetrics(cell), System.currentTimeMillis())
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(2f)) {
                ApiFace(TileSize.WIDE, api.name, api.tile, cached, true, rememberTileMetrics(cell), System.currentTimeMillis())
            }
        }
    }
}

/** The template of [target], editable, with what it gives from the fetched answer under it. */
@Composable
private fun TemplateField(templates: SnapshotStateMap<ApiTarget, String>, target: ApiTarget, root: Any?) {
    val context = LocalContext.current
    val colors = Metro.colors
    val text = templates[target].orEmpty()
    var value by remember(target) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    // A pick from the field list changed it: the field follows.
    if (value.text != text) value = TextFieldValue(text, TextRange(text.length))
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            templates[target] = it.text.take(if (target.isPath) 200 else BuiltInFormulas.MAX_TEMPLATE)
        },
        textStyle = MetroType.body.copy(color = colors.foreground, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(colors.control.copy(alpha = 0.6f))
                    .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.text.isEmpty()) {
                    MetroText(
                        if (target.isPath) "the list, like items or \$" else "text with {fields} and {formulas}",
                        MetroType.body, color = colors.subtle, maxLines = 1,
                    )
                }
                field()
            }
        },
    )
    // What it gives, worked out against the fetched answer (the first item, for a row).
    val result = remember(text, root, target, templates[ApiTarget.LIST]) {
        when {
            text.isBlank() || root == null -> null
            target.isPath -> ApiJson.items(root, text, Int.MAX_VALUE).size.let { if (it == 0) "no list there" to true else "$it items" to false }
            else -> {
                val row = if (target.isRow) ApiJson.items(root, templates[ApiTarget.LIST].orEmpty(), 1).firstOrNull() ?: io.github.danielphingston.metro.live.api.ApiFormulas.NO_ROW else io.github.danielphingston.metro.live.api.ApiFormulas.NO_ROW
                val e = BuiltInFormulas.evaluate(text, root, ApiTiles.formulaContext(context, System.currentTimeMillis()), row)
                (e.error ?: "= ${e.text.ifEmpty { "(empty)" }}") to (e.error != null)
            }
        }
    }
    result?.let { (line, error) ->
        MetroText(line, MetroType.caption, color = if (error) colors.accent else colors.subtle, maxLines = 3, modifier = Modifier.padding(top = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** A one-line field in the editor's style; [secret] hides what is typed. */
@Composable
private fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MetroType.listItem.copy(color = colors.foreground),
        cursorBrush = SolidColor(colors.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
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
                if (value.isEmpty()) MetroText(placeholder, MetroType.listItem, color = colors.subtle, maxLines = 1)
                field()
            }
        },
    )
}

@Composable
private fun Caption(text: String) {
    MetroText(text, MetroType.appName, color = Metro.colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    MetroText(text, MetroType.caption, color = Metro.colors.subtle, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun StatusLine(status: FeedEditorStatus) {
    val colors = Metro.colors
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (status) {
        FeedEditorStatus.None -> Unit
        is FeedEditorStatus.Checking -> MetroText(status.text, MetroType.body, color = colors.subtle, modifier = live.padding(top = 12.dp))
        is FeedEditorStatus.Error -> MetroText(status.text, MetroType.body, color = colors.accent, modifier = live.padding(top = 12.dp))
    }
}
