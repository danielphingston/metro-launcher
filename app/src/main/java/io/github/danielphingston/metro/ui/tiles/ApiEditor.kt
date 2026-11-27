package io.github.danielphingston.metro.ui.tiles

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiTileJson
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.api.ApiCheck
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiRequest
import io.github.danielphingston.metro.live.api.ApiAddress
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
import android.content.ClipData
import android.content.ClipboardManager

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

/** The editor's three faces: the tile, the back it flips to, and the expanded view a tap opens. */
internal enum class ApiEditorTab(val label: String) { TILE("front"), BACK("back"), EXPANDED("expanded view") }

/** Whether [this] can be set on [tab]: the back has no link of its own, a tap does what the front does. */
internal fun ApiTarget.allowedOn(tab: ApiEditorTab): Boolean = !(tab == ApiEditorTab.BACK && this == ApiTarget.LINK)

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

/** Why an address of its own for [which] ("the expanded view", "the back") can't be used, or null. */
private fun ownAddressProblem(address: String, which: String): String? {
    if (address.isBlank() || ApiAddress.normalize(address) != null) return null
    return if (ApiAddress.usesFields(address)) {
        BuiltInFormulas.problem(address)?.let { "$which's address: $it" } ?: "$which's address must make an https address"
    } else {
        "$which's address must be an https address"
    }
}

/** Why [name] can't be saved as it stands, or null. */
internal fun apiTileProblem(
    url: String,
    detailUrl: String,
    tile: Map<ApiTarget, String>,
    detail: Map<ApiTarget, String>,
    headers: List<Pair<String, String>>,
    backUrl: String = "",
    back: Map<ApiTarget, String> = emptyMap(),
): String? {
    ApiAddress.tileProblem(url)?.let { return it }
    ownAddressProblem(detailUrl, "the expanded view")?.let { return it }
    ownAddressProblem(backUrl, "the back")?.let { return it }
    val shown = viewOf(url, tile)
    if (shown.slots.keys.none { it != ApiSlot.LINK } && shown.list == null) return "pick at least one field for the tile to show"
    for ((which, templates) in listOf("tile" to tile, "back" to back, "expanded view" to detail)) {
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
 * Sets up an API tile on two pages. Import, first: paste a setup (or copy the prompt for a
 * chatbot to write one), see the tile it makes, and type any key it needs. Advanced: the same
 * setup by hand, in parts that open one at a time: the source (address and headers, fetched to
 * pick from), the faces (the tile, the back it flips to, and the expanded view a tap opens, each
 * built from the answer or picked field by field into templates with formulas, see
 * [BuiltInFormulas], with a live preview), and how often it updates.
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
    /** A tile setup shared in from another app, shown in the import box to read on a tap. */
    initialImport: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val created = remember { initial?.created?.takeIf { it != 0L } ?: System.currentTimeMillis() }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var url by remember { mutableStateOf(initial?.tile?.url.orEmpty()) }
    var detailUrl by remember { mutableStateOf(initial?.detail?.url.orEmpty()) }
    var separateDetail by remember { mutableStateOf(!initial?.detail?.url.isNullOrEmpty()) }
    var backUrl by remember { mutableStateOf(initial?.back?.url.orEmpty()) }
    var separateBack by remember { mutableStateOf(!initial?.back?.url.isNullOrEmpty()) }
    val headers = remember { mutableStateListOf<HeaderRow>().apply { initial?.headers?.forEach { add(HeaderRow(it, "")) } } }
    val tileTemplates = remember { mutableStateMapOf<ApiTarget, String>().apply { initial?.let { putAll(templatesOf(it.tile)) } } }
    val detailTemplates = remember { mutableStateMapOf<ApiTarget, String>().apply { initial?.let { putAll(templatesOf(it.detail)) } } }
    val backTemplates = remember { mutableStateMapOf<ApiTarget, String>().apply { initial?.let { putAll(templatesOf(it.back)) } } }
    var refresh by remember { mutableStateOf(initial?.refreshMinutes ?: 15) }
    var liveOn by remember { mutableStateOf((initial?.liveSeconds ?: 0) > 0) }
    var liveSeconds by remember { mutableStateOf(initial?.liveSeconds?.takeIf { it > 0 } ?: 15) }
    var wifiOnly by remember { mutableStateOf(initial?.wifiOnly ?: false) }
    // A new tile starts on the import; an edited one on what it has, under advanced.
    var page by remember { mutableStateOf(if (initial == null || initialImport != null) EditorPage.IMPORT else EditorPage.ADVANCED) }
    var sourceOpen by remember { mutableStateOf(initial == null) }
    var facesOpen by remember { mutableStateOf(true) }
    var updatesOpen by remember { mutableStateOf(false) }
    var functionsOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(ApiEditorTab.TILE) }
    var target by remember { mutableStateOf(ApiTarget.VALUE) }
    var status by remember { mutableStateOf<FeedEditorStatus>(FeedEditorStatus.None) }
    // Which address the status line is about, so it shows next to that address.
    var statusFor by remember { mutableStateOf(ApiEditorTab.TILE) }
    var tileRoot by remember { mutableStateOf(sample) }
    var prompt by remember { mutableStateOf("") }
    var building by remember { mutableStateOf<BuildState?>(null) }
    var importText by remember { mutableStateOf(initialImport.orEmpty().take(ApiImport.MAX_CHARS)) }
    var ai by remember { mutableStateOf<TileAi?>(null) }
    var aiUi by remember { mutableStateOf<AiUi>(AiUi.Idle) }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    // The model is opened on the first AI tap and closed with the editor: nothing stays loaded.
    DisposableEffect(Unit) { onDispose { ai?.close() } }
    var detailRoot by remember { mutableStateOf<Any?>(null) }
    var backRoot by remember { mutableStateOf<Any?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var saveProblem by remember { mutableStateOf<String?>(null) }

    // Saved header values: read once, so an edited tile keeps its key without showing it.
    LaunchedEffect(Unit) {
        if (initial == null || initial.headers.isEmpty()) return@LaunchedEffect
        val stored = withContext(Dispatchers.IO) { ApiSecrets.of(context).get(created) }
        headers.forEach { row -> stored[row.name]?.let { row.value = it } }
    }

    fun headerMap(): Map<String, String> = headers.mapNotNull { r -> r.name.trim().takeIf { it.isNotEmpty() && r.value.isNotEmpty() }?.let { it to r.value } }.toMap()

    /** Fetches the answer [which] reads: the tile's address, or the back's or expanded view's own. */
    fun fetch(which: ApiEditorTab) {
        if (job?.isActive == true) return
        statusFor = which
        val own = when (which) {
            ApiEditorTab.TILE -> null
            ApiEditorTab.BACK -> backUrl.takeIf { separateBack }
            ApiEditorTab.EXPANDED -> detailUrl.takeIf { separateDetail }
        }
        val label = if (which == ApiEditorTab.BACK) "the back" else "the expanded view"
        val fromTile = own != null && ApiAddress.usesFields(own)
        val address = if (fromTile) {
            // An address built from the tile's answer is worked out from the answer fetched above.
            if (ApiAddress.normalize(own!!) == null) {
                status = FeedEditorStatus.Error(ownAddressProblem(own, label) ?: "$label's address must make an https address")
                return
            }
            when (val r = ApiAddress.resolve(own, tileRoot, tileRoot != null, ApiTiles.formulaContext(context, System.currentTimeMillis()))) {
                is ApiAddress.Resolved.Url -> r.url
                ApiAddress.Resolved.Waiting -> {
                    status = FeedEditorStatus.Error("fetch the tile's address first: this address uses its answer")
                    return
                }
                is ApiAddress.Resolved.Problem -> {
                    status = FeedEditorStatus.Error(r.message.replace("the expanded view", label))
                    return
                }
            }
        } else {
            if (own != null) ApiStore.address(own) else ApiAddress.tileAddress(url)
        }
        if (address == null) {
            status = FeedEditorStatus.Error(
                if (own != null) "$label's address must be https" else ApiAddress.tileProblem(url)?.takeIf { ApiAddress.usesFields(url) } ?: "enter an https address that answers with JSON",
            )
            return
        }
        when {
            own == null -> url = address
            fromTile -> Unit
            which == ApiEditorTab.BACK -> backUrl = address
            else -> detailUrl = address
        }
        val sent = if (fromTile) ApiAddress.headersFor(own!!, address, ApiAddress.tileAddress(url).orEmpty(), headerMap()) else headerMap()
        job = scope.launch {
            status = FeedEditorStatus.Checking("fetching ${runCatching { URL(address).host }.getOrDefault(address)}…")
            when (val result = ApiStore.check(context, ApiRequest(address, sent))) {
                is ApiCheck.Ok -> {
                    when (which) {
                        ApiEditorTab.TILE -> {
                            tileRoot = result.root
                            if (!separateDetail) detailRoot = result.root
                            if (!separateBack) backRoot = result.root
                        }
                        ApiEditorTab.BACK -> backRoot = result.root
                        ApiEditorTab.EXPANDED -> detailRoot = result.root
                    }
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
        if (initial.back.url.isEmpty()) backRoot = tileRoot
    }

    /** Fetches the answer an address of a face's own reads, built from the tile's [root]; null if it can't. */
    suspend fun ownAnswer(own: String, root: Any?, tileAddress: String): Any? {
        if (own.isEmpty() || root == null) return null
        val r = ApiAddress.resolve(own, root, true, ApiTiles.formulaContext(context, System.currentTimeMillis()))
        if (r !is ApiAddress.Resolved.Url) return null
        return (ApiStore.check(context, ApiRequest(r.url, ApiAddress.headersFor(own, r.url, tileAddress, headerMap()))) as? ApiCheck.Ok)?.root
    }

    /** Reads a pasted or shared setup, fetches the answers it needs, and shows what works of it to use or discard. */
    fun runImport() {
        val parsed = ApiImport.parse(importText)
        if (parsed is ApiImport.Parsed.Failed) {
            building = BuildState.Note((listOf(parsed.message) + parsed.errors).joinToString("\n· "))
            return
        }
        parsed as ApiImport.Parsed.Ok
        if (job?.isActive == true) return
        job = scope.launch {
            building = BuildState.Working("checking it against the API's answer…")
            val draft = parsed.draft
            val notes = ArrayList(parsed.notes)
            val address = ApiAddress.tileAddress(draft.url.ifEmpty { url })
            var root: Any? = if (address != null && address == ApiAddress.tileAddress(url)) tileRoot else null
            if (address == null) {
                notes += "no https address to fetch: add one under advanced, then import again to check it"
            } else if (root == null) {
                when (val r = ApiStore.check(context, ApiRequest(address, headerMap()))) {
                    is ApiCheck.Ok -> root = r.root
                    is ApiCheck.Failed -> notes += "couldn't fetch the answer to check it: ${r.message}"
                }
            }
            val detailAnswer = address?.let { ownAnswer(draft.detailUrl, root, it) }
            val backAnswer = address?.let { ownAnswer(draft.backUrl, root, it) }
            val checked = runCatching { ApiImport.check(draft, root, detailAnswer, ApiTiles.formulaContext(context, System.currentTimeMillis()), backAnswer) }
                .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; building = BuildState.Note("couldn't check the import (${it.javaClass.simpleName})"); return@launch }
            building = if (checked.draft.tile.isEmpty() && checked.draft.detail.isEmpty() && checked.draft.back.isEmpty()) {
                BuildState.Note("nothing in it works with this answer: " + (notes + checked.notes).take(3).joinToString("; "))
            } else {
                BuildState.Imported(checked.copy(notes = notes + checked.notes), "imported", root, detailAnswer, backAnswer)
            }
        }
    }

    /** Asks the phone's own model for a setup from the prompt; checks the model first, and offers its download. */
    fun runAi() {
        if (aiJob?.isActive == true) return
        val answer = tileRoot
        if (answer == null) {
            aiUi = AiUi.Failed("fetch the address first: the AI reads its answer")
            return
        }
        aiJob = scope.launch {
            val model = ai ?: TileAis.open(context).also { ai = it }
            aiUi = AiUi.Checking
            when (val state = model.state()) {
                AiState.Ready -> {
                    aiUi = AiUi.Thinking
                    aiUi = try {
                        when (val parsed = askTileAi(model, answer, url, prompt)) {
                            is ApiImport.Parsed.Failed -> AiUi.Failed((listOf("the model's answer wasn't a tile setup: ${parsed.message}") + parsed.errors.take(5)).joinToString("\n· "))
                            is ApiImport.Parsed.Ok -> {
                                // The model sets what shows, never the address or its headers.
                                val draft = parsed.draft.copy(url = "", headers = emptyList(), name = parsed.draft.name.ifBlank { name })
                                val checked = ApiImport.check(draft, answer, null, ApiTiles.formulaContext(context, System.currentTimeMillis()))
                                if (checked.draft.tile.isEmpty()) {
                                    AiUi.Failed("nothing the model wrote works with this answer: " + checked.notes.take(2).joinToString("; "))
                                } else {
                                    building = BuildState.Imported(checked.copy(notes = parsed.notes + checked.notes), "written by the on-device AI", answer, null)
                                    AiUi.Ready
                                }
                            }
                        }
                    } catch (e: TileAiException) {
                        AiUi.Failed(e.message ?: "the model failed")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AiUi.Failed("the model failed (${e.javaClass.simpleName})")
                    }
                }
                AiState.Downloadable -> aiUi = AiUi.Downloadable
                AiState.Downloading -> aiUi = AiUi.Downloading("the model is downloading")
                is AiState.Unavailable -> aiUi = AiUi.Unavailable(state.reason)
            }
        }
    }

    fun downloadAi() {
        val model = ai ?: return
        aiJob?.cancel()
        aiJob = scope.launch {
            aiUi = AiUi.Downloading("starting the download…")
            model.download().collect { d ->
                aiUi = when (d) {
                    is AiDownload.Started -> AiUi.Downloading("downloading ${megabytes(d.bytes)}…")
                    is AiDownload.Progress -> AiUi.Downloading("downloaded ${megabytes(d.bytes)}…")
                    AiDownload.Done -> AiUi.Ready
                    is AiDownload.Failed -> AiUi.Failed(d.reason)
                }
            }
        }
    }

    fun copyPrompt() {
        val text = ApiImport.prompt(ApiAddress.tileAddress(url).orEmpty(), prompt, tileRoot)
        val copied = runCatching {
            context.getSystemService(ClipboardManager::class.java)!!.setPrimaryClip(ClipData.newPlainText("tile prompt", text))
        }.isSuccess
        building = BuildState.Note(
            if (copied) "prompt copied: paste it into Gemini or any chatbot, then paste its answer above" else "couldn't copy the prompt",
        )
    }

    /** Puts an imported or AI-written setup into the editor. Header values are never imported: their rows wait for a value. */
    fun useImported(b: BuildState.Imported) {
        val d = b.checked.draft
        if (d.name.isNotBlank()) name = d.name
        if (d.url.isNotEmpty() && d.url != url) {
            url = d.url
            tileRoot = b.root
        }
        d.headers.forEach { h -> if (headers.none { it.name.equals(h, ignoreCase = true) }) headers += HeaderRow(h, "") }
        tileTemplates.clear()
        tileTemplates.putAll(d.tile)
        separateDetail = d.detailUrl.isNotEmpty()
        detailUrl = d.detailUrl
        detailTemplates.clear()
        detailTemplates.putAll(d.detail)
        detailRoot = if (separateDetail) b.detailRoot else b.root
        separateBack = d.backUrl.isNotEmpty()
        backUrl = d.backUrl
        backTemplates.clear()
        backTemplates.putAll(d.back - ApiTarget.LINK)
        backRoot = if (separateBack) b.backRoot else b.root
        d.refreshMinutes?.let { refresh = it }
        d.liveSeconds?.let { s -> liveOn = s > 0; if (s > 0) liveSeconds = s }
        tab = ApiEditorTab.TILE
        target = d.tile.keys.firstOrNull() ?: ApiTarget.TITLE
        // An AI-written setup was asked for under advanced; an import stays on its page, where the keys are asked for.
        building = null
    }

    fun done() {
        val pairs = headers.map { it.name.trim() to it.value }.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
        val problem = apiTileProblem(url, if (separateDetail) detailUrl else "", tileTemplates, detailTemplates, pairs, if (separateBack) backUrl else "", backTemplates)
        saveProblem = problem
        if (problem != null) return
        val tileUrl = ApiAddress.tileAddress(url)!!
        val content = TileContent.Api(
            name = name.trim().ifEmpty { runCatching { URL(tileUrl).host }.getOrDefault("api") }.take(ApiTileJson.MAX_NAME),
            tile = viewOf(tileUrl, tileTemplates),
            detail = viewOf(if (separateDetail) ApiAddress.normalize(detailUrl).orEmpty() else "", detailTemplates),
            headers = pairs.map { it.first },
            refreshMinutes = refresh,
            liveSeconds = if (liveOn) liveSeconds else 0,
            wifiOnly = wifiOnly,
            created = created,
            back = viewOf(if (separateBack) ApiAddress.normalize(backUrl).orEmpty() else "", backTemplates - ApiTarget.LINK),
        )
        val values = pairs.toMap()
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { ApiSecrets.of(context).put(created, values) } }
            onDone(content)
        }
    }

    val templates = when (tab) {
        ApiEditorTab.TILE -> tileTemplates
        ApiEditorTab.BACK -> backTemplates
        ApiEditorTab.EXPANDED -> detailTemplates
    }
    val root = when (tab) {
        ApiEditorTab.TILE -> tileRoot
        ApiEditorTab.BACK -> backRoot ?: tileRoot
        ApiEditorTab.EXPANDED -> detailRoot ?: tileRoot
    }
    val colors = Metro.colors
    // The tile as set up so far: what the previews draw.
    val draft = TileContent.Api(
        name.ifBlank { "api" }, viewOf(url, tileTemplates), viewOf(detailUrl, detailTemplates), created = created,
        back = viewOf(if (separateBack) backUrl else "", backTemplates),
    )
    val hasSetup = tileTemplates.values.any { it.isNotBlank() }
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
                .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
        ) {
            // Windows Phone pivots: the app's name small on top, the two pages as big headers.
            MetroText("API TILE", MetroType.appName, color = colors.subtle)
            Pivots(page, { page = it; building = null })
            Spacer(Modifier.height(16.dp))

            if (page == EditorPage.IMPORT) {
                // The main path: paste a setup, or have a chatbot write one. Nothing else on this page.
                MetroText(
                    "Paste a tile setup to add it. No setup yet? Copy the prompt, paste it into Gemini or any chatbot with the API's address and what you want to see, then paste its answer here.",
                    MetroType.body, color = colors.foreground,
                )
                Spacer(Modifier.height(12.dp))
                ImportField(importText, { importText = it.take(ApiImport.MAX_CHARS) })
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetroButton("read import", ::runImport)
                    MetroButton("copy prompt", ::copyPrompt)
                    if (importText.isNotEmpty()) MetroButton("clear", { importText = "" })
                }
                BuildNote(building, onUse = ::useImported, onDiscard = { building = null }, name = name, url = url, created = created)
                // Keys the setup names: the one thing an import can't bring, asked for right here.
                val missing = headers.filter { it.name.isNotBlank() && it.value.isEmpty() }
                if (missing.isNotEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Caption("KEYS IT NEEDS")
                    missing.forEach { row ->
                        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            MetroText(row.name, MetroType.body, color = colors.foreground, maxLines = 1, modifier = Modifier.weight(1f))
                            EditorField(row.value, { row.value = it.take(1024) }, "value", Modifier.weight(1f), secret = true)
                        }
                    }
                    Hint("Kept encrypted on this phone: never backed up or exported.")
                }
                if (hasSetup) {
                    Spacer(Modifier.height(20.dp))
                    Caption("YOUR TILE")
                    PreviewFaces(draft, tileRoot, if (separateBack) backRoot else tileRoot, if (separateDetail) detailRoot else tileRoot)
                    Hint("Change anything under advanced.")
                }
            } else {
                // Advanced: the same setup by hand, in three parts that open one at a time as needed.
                EditorSection(
                    "source", sourceSummary(url, headers), sourceOpen, { sourceOpen = it },
                ) {
                    Caption("NAME")
                    EditorField(name, { name = it.take(ApiTileJson.MAX_NAME) }, "shown on the tile")
                    Spacer(Modifier.height(16.dp))
                    Caption("ADDRESS")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditorField(url, { url = it; status = FeedEditorStatus.None }, "api.example.com/data.json", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                        MetroButton("fetch", { fetch(ApiEditorTab.TILE) })
                    }
                    Hint("https only, answering with JSON (up to 1 MB). Formulas can follow the site, like {random(1, 1025)} or {dayOfYear()}, worked out on every refresh. Fetch it to pick what to show.")
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
                    if (statusFor == ApiEditorTab.TILE) StatusLine(status)
                }

                EditorSection("faces", "front, back and the expanded view", facesOpen, { facesOpen = it }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiEditorTab.entries.forEach { t ->
                            val filled = when (t) {
                                ApiEditorTab.TILE -> tileTemplates
                                ApiEditorTab.BACK -> backTemplates
                                ApiEditorTab.EXPANDED -> detailTemplates
                            }.values.any { it.isNotBlank() }
                            MetroChoice(if (filled && t != ApiEditorTab.TILE) "${t.label} ✓" else t.label, tab == t, { tab = t; if (!target.allowedOn(t)) target = ApiTarget.TITLE })
                        }
                    }
                    Hint(
                        when (tab) {
                            ApiEditorTab.TILE -> "What the tile shows on Start."
                            ApiEditorTab.BACK -> "What the tile flips to now and then, like a live tile. Leave it empty and the tile doesn't flip."
                            ApiEditorTab.EXPANDED -> "What a tap on the tile opens. Empty parts show what the tile shows."
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    if (tab != ApiEditorTab.TILE) {
                        val back = tab == ApiEditorTab.BACK
                        val separate = if (back) separateBack else separateDetail
                        MetroToggle(
                            "Same address as the tile", !separate,
                            { same -> if (back) { separateBack = !same; backRoot = null } else { separateDetail = !same; detailRoot = null } },
                            description = if (separate) "Reads its own address" else "Reads the tile's address",
                        )
                        if (separate) {
                            val own = if (back) backUrl else detailUrl
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                EditorField(own, { if (back) backUrl = it else detailUrl = it }, "api.example.com/live.json", Modifier.weight(1f), keyboard = KeyboardType.Uri)
                                MetroButton("fetch", { fetch(tab) })
                            }
                            Hint("Can use the tile's answer: {species.url} follows a link in it, api.example.com/items/{id} builds one. Read again each time the tile updates. The tile's headers go only to its own site.")
                            // A link or id from the tile's answer, picked like any field.
                            ApiFieldPicker(
                                root = tileRoot,
                                mode = PickMode.ADDRESS,
                                onPick = { node ->
                                    if (back) { backUrl = withAddressField(backUrl, node.path); backRoot = null } else { detailUrl = withAddressField(detailUrl, node.path); detailRoot = null }
                                },
                                onUseList = {},
                                startOpen = false,
                            )
                        }
                        if (statusFor == tab) StatusLine(status)
                        Spacer(Modifier.height(12.dp))
                    }

                    // Fills this face from its answer: the prompt's words matched to fields, or a best guess.
                    Caption("BUILD FROM THE ANSWER")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        EditorField(prompt, { prompt = it.take(MAX_PROMPT) }, "name, weight and moves as rows", Modifier.weight(1f))
                        MetroButton("build", {
                            building = if (root == null) {
                                BuildState.Note("fetch the address first: the build reads its answer")
                            } else {
                                val result = ApiSuggest.suggest(root, prompt)
                                if (result.isEmpty) BuildState.Note("found nothing to show in this answer: pick fields below") else BuildState.Pending(tab, result)
                            }
                        })
                    }
                    Hint("Build matches your words to the answer's fields. Leave it empty for a best guess.")
                    Box(Modifier.padding(top = 10.dp)) { MetroButton("build with AI", ::runAi) }
                    AiLine(aiUi, onDownload = ::downloadAi)
                    when (val b = building) {
                        is BuildState.Pending -> if (b.tab == tab) {
                            Spacer(Modifier.height(12.dp))
                            val built = when (tab) {
                                ApiEditorTab.TILE -> draft.copy(tile = viewOf(url, b.result.templates))
                                ApiEditorTab.BACK -> draft.copy(back = viewOf(draft.back.url, b.result.templates))
                                ApiEditorTab.EXPANDED -> draft.copy(detail = viewOf(detailUrl, b.result.templates))
                            }
                            BuildPreview(b.result, built, tab, root)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MetroButton("use this", {
                                    templates.clear()
                                    templates.putAll(b.result.templates)
                                    target = b.result.templates.keys.firstOrNull { it.allowedOn(tab) } ?: ApiTarget.TITLE
                                    building = null
                                })
                                MetroButton("discard", { building = null })
                            }
                        }
                        // An AI-written setup comes back as a whole one, shown here where it was asked for.
                        else -> BuildNote(b, onUse = ::useImported, onDiscard = { building = null }, name = name, url = url, created = created)
                    }
                    Spacer(Modifier.height(16.dp))

                    // The preview, from the fetched answer.
                    Caption("PREVIEW")
                    val cached = root?.let { CachedApi("preview", it, "", System.currentTimeMillis()) }
                    when (tab) {
                        ApiEditorTab.TILE -> PreviewTiles(draft, cached)
                        ApiEditorTab.BACK -> PreviewTiles(draft, cached, back = true)
                        ApiEditorTab.EXPANDED -> Box(Modifier.fillMaxWidth().border(1.dp, colors.control).padding(12.dp)) {
                            ApiDetailBody(draft, cached, System.currentTimeMillis(), live = false, paused = false, maxRows = 5)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Caption("SHOW")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ApiTarget.entries.filter { it.allowedOn(tab) }.forEach { t ->
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

                    if (!target.isPath) {
                        Spacer(Modifier.height(12.dp))
                        // The functions are a reference: closed until asked for.
                        MetroButton(if (functionsOpen) "hide functions" else "functions", { functionsOpen = !functionsOpen })
                        if (functionsOpen) {
                            Spacer(Modifier.height(10.dp))
                            Caption("TAP TO ADD")
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
                    }
                }

                EditorSection("updates", updatesSummary(refresh, liveOn, liveSeconds), updatesOpen, { updatesOpen = it }) {
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
            }
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

/** The editor's two pages: the import first, everything else under advanced. */
internal enum class EditorPage(val label: String) { IMPORT("import"), ADVANCED("advanced") }

/** Windows Phone pivot headers: the page showing in full colour, the other dimmed beside it. */
@Composable
private fun Pivots(page: EditorPage, onPage: (EditorPage) -> Unit) {
    val colors = Metro.colors
    Row(Modifier.semantics { isTraversalGroup = true }, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        EditorPage.entries.forEach { p ->
            MetroText(
                p.label, MetroType.style(34.sp, FontWeight.Light), color = if (p == page) colors.foreground else colors.subtle, maxLines = 1,
                modifier = Modifier
                    .clickable(role = Role.Tab, onClickLabel = "show ${p.label}") { onPage(p) }
                    .semantics { selected = p == page }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * A part of the advanced page that opens and closes: its title and a one-line summary of what
 * is set, so a closed part still says where things stand.
 */
@Composable
private fun EditorSection(title: String, summary: String, open: Boolean, onOpen: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = Metro.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = if (open) "close $title" else "open $title") { onOpen(!open) }
                .semantics(mergeDescendants = true) { stateDescription = if (open) "open" else "closed" }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                MetroText(title, MetroType.sectionTitle, color = colors.foreground)
                if (summary.isNotEmpty()) MetroText(summary, MetroType.caption, color = colors.subtle, maxLines = 1)
            }
            MetroText(if (open) "–" else "+", MetroType.sectionTitle, color = colors.accent)
        }
        if (open) Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), content = content)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.control))
    }
}

/** "pokeapi.co · 1 key", or what is missing. */
private fun sourceSummary(url: String, headers: List<HeaderRow>): String {
    val host = ApiAddress.tileAddress(url)?.let { runCatching { URL(it.replace(Regex("\\{[^{}]*\\}"), "x")).host }.getOrNull() }
    val keys = headers.count { it.name.isNotBlank() }
    return listOfNotNull(host ?: "no address yet", if (keys == 0) null else if (keys == 1) "1 key" else "$keys keys").joinToString(" · ")
}

/** "every 15 min · live updates off". */
private fun updatesSummary(refresh: Int, liveOn: Boolean, liveSeconds: Int): String =
    "every $refresh min · " + if (liveOn) "live every $liveSeconds s" else "live updates off"

/** A note, a step under way, or a whole setup (imported or AI-written) with use this and discard. */
@Composable
private fun BuildNote(building: BuildState?, onUse: (BuildState.Imported) -> Unit, onDiscard: () -> Unit, name: String, url: String, created: Long) {
    val colors = Metro.colors
    when (building) {
        null, is BuildState.Pending -> Unit
        is BuildState.Note -> MetroText(building.text, MetroType.body, color = colors.accent, modifier = Modifier.padding(top = 12.dp))
        is BuildState.Working -> MetroText(building.text, MetroType.body, color = colors.subtle, modifier = Modifier.padding(top = 12.dp))
        is BuildState.Imported -> {
            Spacer(Modifier.height(12.dp))
            ImportPreview(building, name, url, created)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetroButton("use this", { onUse(building) })
                MetroButton("discard", onDiscard)
            }
        }
    }
}


/** [current] with the field at [path] added: a picture or link takes the field alone; text gets it appended. */
/**
 * [current] address with the field at [path] put in: after a "/" or "=" it builds on the address
 * (api.example.com/items/{id}); otherwise the field (a whole link in the answer) is the address.
 */
internal fun withAddressField(current: String, path: String): String {
    val text = current.trim()
    return if (text.isNotEmpty() && text.last() in "/=-_") "$text{$path}" else "{$path}"
}

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

/** Where a build stands: nothing, a note on why it made nothing, or a result waiting for use this or discard. */
internal sealed interface BuildState {
    data class Note(val text: String) : BuildState
    data class Working(val text: String) : BuildState
    data class Pending(val tab: ApiEditorTab, val result: ApiSuggest.Result) : BuildState

    /** A whole setup, imported or written by the AI and checked: [root] and [detailRoot] are the answers it was checked against. */
    data class Imported(val checked: ApiImport.Checked, val source: String, val root: Any?, val detailRoot: Any?, val backRoot: Any? = null) : BuildState
}

/** Where the on-device AI stands in the editor: nothing until the first tap. */
internal sealed interface AiUi {
    data object Idle : AiUi
    data object Checking : AiUi
    data object Ready : AiUi
    data object Thinking : AiUi
    data object Downloadable : AiUi
    data class Downloading(val text: String) : AiUi
    data class Unavailable(val reason: String) : AiUi
    data class Failed(val reason: String) : AiUi
}

private fun megabytes(bytes: Long): String = if (bytes <= 0) "" else "%.0f MB".format(java.util.Locale.US, bytes / 1_000_000.0)

/** The on-device AI's state, with the download offered when the model can be fetched. */
@Composable
private fun AiLine(state: AiUi, onDownload: () -> Unit) {
    val colors = Metro.colors
    val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(top = 10.dp)
    val text = when (state) {
        AiUi.Idle -> return
        AiUi.Checking -> "on-device AI: checking this phone…"
        AiUi.Ready -> "on-device AI: ready"
        AiUi.Thinking -> "on-device AI: writing the tile…"
        AiUi.Downloadable -> "on-device AI: the model isn't on this phone yet. Android downloads it (a few GB, best on Wi-Fi)."
        is AiUi.Downloading -> "on-device AI: ${state.text} Keep this open; tap build with AI when it's done."
        is AiUi.Unavailable -> "on-device AI isn't available: ${state.reason}. Build, import and copy prompt still work."
        is AiUi.Failed -> "on-device AI: ${state.reason}"
    }
    val warn = state is AiUi.Unavailable || state is AiUi.Failed
    MetroText(text, MetroType.body, color = if (warn) colors.accent else colors.subtle, modifier = live)
    if (state == AiUi.Downloadable) Box(Modifier.padding(top = 8.dp)) { MetroButton("download model", onDownload) }
}

/** A box for pasting a tile's setup: several lines, in a fixed-width font. */
@Composable
private fun ImportField(value: String, onValueChange: (String) -> Unit) {
    val colors = Metro.colors
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = MetroType.caption.copy(color = colors.foreground, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = "tile setup to import" },
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
                    .background(colors.control.copy(alpha = 0.6f))
                    .then(if (focused) Modifier.border(2.dp, colors.accent) else Modifier)
                    .padding(12.dp),
            ) {
                if (value.isEmpty()) MetroText("{ \"name\": …, \"tile\": { … } }", MetroType.caption, color = colors.subtle)
                field()
            }
        },
    )
}

/** An imported or AI-written setup before it is used: the tile it makes, and everything that was left out and why. */
@Composable
private fun ImportPreview(b: BuildState.Imported, name: String, url: String, created: Long) {
    val colors = Metro.colors
    val d = b.checked.draft
    Column(Modifier.fillMaxWidth().border(1.dp, colors.accent).padding(12.dp)) {
        MetroText("${b.source}: use this to replace the tile's setup", MetroType.caption, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
        val draft = TileContent.Api(
            d.name.ifBlank { name.ifBlank { "api" } }, viewOf(d.url.ifEmpty { url }, d.tile), viewOf(d.detailUrl, d.detail), created = created,
            back = viewOf(d.backUrl, d.back),
        )
        PreviewFaces(
            draft, b.root,
            backRoot = if (d.backUrl.isNotEmpty()) b.backRoot else b.root,
            detailRoot = if (d.detailUrl.isNotEmpty()) b.detailRoot else b.root,
            rows = 3,
        )
        Spacer(Modifier.height(8.dp))
        if (d.url.isNotEmpty() && d.url != url) MetroText("address: ${d.url}", MetroType.caption, color = colors.foreground, maxLines = 2)
        d.tile.forEach { (t, text) -> MetroText("${t.label}: $text", MetroType.caption, color = colors.foreground, maxLines = 1) }
        if (d.backUrl.isNotEmpty()) MetroText("back address: ${d.backUrl}", MetroType.caption, color = colors.foreground, maxLines = 2)
        d.back.forEach { (t, text) -> MetroText("back ${t.label}: $text", MetroType.caption, color = colors.foreground, maxLines = 1) }
        if (d.detailUrl.isNotEmpty()) MetroText("expanded view address: ${d.detailUrl}", MetroType.caption, color = colors.foreground, maxLines = 2)
        d.detail.forEach { (t, text) -> MetroText("expanded ${t.label}: $text", MetroType.caption, color = colors.foreground, maxLines = 1) }
        if (d.headers.isNotEmpty()) MetroText("headers to fill in: ${d.headers.joinToString(", ")}", MetroType.caption, color = colors.foreground)
        // Every error, none hidden: what was skipped and why.
        if (b.checked.notes.isNotEmpty()) {
            MetroText("skipped (${b.checked.notes.size}):", MetroType.caption, color = colors.accent, modifier = Modifier.padding(top = 6.dp))
            b.checked.notes.forEach { MetroText("· $it", MetroType.caption, color = colors.accent) }
        }
    }
}

/** A build prompt is at most this long. */
internal const val MAX_PROMPT = 200

/** What a build would make, before it replaces this face's fields: the face, what each part reads, and the words that matched nothing. */
@Composable
private fun BuildPreview(result: ApiSuggest.Result, built: TileContent.Api, tab: ApiEditorTab, root: Any?) {
    val colors = Metro.colors
    val cached = root?.let { CachedApi("build", it, "", System.currentTimeMillis()) }
    Column(Modifier.fillMaxWidth().border(1.dp, colors.accent).padding(12.dp)) {
        MetroText("built from the answer: use this to replace this face's fields", MetroType.caption, color = colors.subtle, modifier = Modifier.padding(bottom = 8.dp))
        when (tab) {
            ApiEditorTab.TILE -> PreviewTiles(built, cached)
            ApiEditorTab.BACK -> PreviewTiles(built, cached, back = true)
            ApiEditorTab.EXPANDED -> ApiDetailBody(built, cached, System.currentTimeMillis(), live = false, paused = false, maxRows = 3)
        }
        Spacer(Modifier.height(8.dp))
        result.templates.forEach { (t, text) ->
            MetroText("${t.label}: $text", MetroType.caption, color = colors.foreground, maxLines = 1)
        }
        if (result.unmatched.isNotEmpty()) {
            MetroText("no field for: ${result.unmatched.joinToString(", ")}", MetroType.caption, color = colors.accent, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** The tile (or its [back]) at medium and wide size, from the fetched answer. */
@Composable
private fun PreviewTiles(api: TileContent.Api, cached: CachedApi?, back: Boolean = false, small: Boolean = !back) {
    val view = if (back) api.back else api.tile
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Four columns as on Start, with its 8 dp gaps.
        val cell: Dp = (maxWidth - 24.dp) / 4
        val now = System.currentTimeMillis()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(cell * 2 + 8.dp).aspectRatio(1f)) {
                    ApiFace(TileSize.MEDIUM, api.name, view, cached, true, rememberTileMetrics(cell), now)
                }
                // A small tile never flips: the back is shown at medium and wide only.
                if (small) {
                    Box(Modifier.width(cell).aspectRatio(1f)) {
                        ApiFace(TileSize.SMALL, api.name, view, cached, true, rememberTileMetrics(cell), now)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(2f)) {
                ApiFace(TileSize.WIDE, api.name, view, cached, true, rememberTileMetrics(cell), now)
            }
        }
    }
}

/**
 * The whole tile at a glance: its front, the back it flips to (if it has one) and the expanded
 * view a tap opens, each from the answer it reads.
 */
@Composable
private fun PreviewFaces(api: TileContent.Api, root: Any?, backRoot: Any?, detailRoot: Any?, rows: Int = 3) {
    val colors = Metro.colors
    val now = System.currentTimeMillis()
    @Composable
    fun label(text: String) = MetroText(text, MetroType.caption, color = colors.subtle, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
    if (root == null) {
        MetroText("fetch the address to see the tile", MetroType.caption, color = colors.subtle)
        return
    }
    label("front")
    PreviewTiles(api, CachedApi("preview", root, "", now))
    if (api.hasBack) {
        label("back, shown now and then")
        if (backRoot != null) {
            PreviewTiles(api, CachedApi("preview-back", backRoot, "", now), back = true)
        } else {
            MetroText("reads its own address: fetch it under advanced to see it", MetroType.caption, color = colors.subtle)
        }
    }
    if (api.detail.slots.isNotEmpty() || api.detail.list != null) {
        label("expanded view, on a tap")
        Box(Modifier.fillMaxWidth().border(1.dp, colors.subtle.copy(alpha = 0.4f)).padding(8.dp)) {
            if (detailRoot != null) {
                ApiDetailBody(api, CachedApi("preview-detail", detailRoot, "", now), now, live = false, paused = false, maxRows = rows)
            } else {
                MetroText("reads its own address: fetch it under advanced to see it", MetroType.caption, color = colors.subtle)
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
