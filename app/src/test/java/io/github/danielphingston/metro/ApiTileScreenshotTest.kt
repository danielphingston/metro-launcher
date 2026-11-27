package io.github.danielphingston.metro

import androidx.compose.ui.test.hasContentDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiProblem
import io.github.danielphingston.metro.live.api.CachedApi
import io.github.danielphingston.metro.ui.start.TileMetrics
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.ApiDetailBody
import io.github.danielphingston.metro.ui.tiles.ApiEditor
import io.github.danielphingston.metro.ui.tiles.ApiFace
import io.github.danielphingston.metro.ui.tiles.AiDownload
import io.github.danielphingston.metro.ui.tiles.AiState
import io.github.danielphingston.metro.ui.tiles.TileAi
import io.github.danielphingston.metro.ui.tiles.TileAis
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/**
 * API tiles: each size with data, a picture and rows; the loading, failed, locked and stale faces;
 * the expanded view; and the editor set up from a sample answer. Pictures are drawn stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ApiTileScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** 2026-11-27T07:00:00Z. */
        const val NOW = 1_795_762_800_000L
        const val URL = "https://api.example.com/race.json"
    }

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private val doc = JSONObject(
        """
        {"race": {"name": "Winter Grand Prix", "circuit": "Lakeside", "start": "2026-11-27T09:30:00Z", "laps": 58, "length_km": 5.4},
         "picture": "https://img.example.com/track.png",
         "standings": [
           {"driver": "A. Rivera", "team": "Comet", "points": 312, "photo": "https://img.example.com/1.png"},
           {"driver": "K. Osei", "team": "Halcyon", "points": 298.5, "photo": "https://img.example.com/2.png"},
           {"driver": "M. Lindqvist", "team": "Comet", "points": 251, "photo": "https://img.example.com/3.png"},
           {"driver": "J. Park", "team": "Vesper", "points": 240, "photo": "https://img.example.com/4.png"},
           {"driver": "T. Moreau", "team": "Halcyon", "points": 187, "photo": "https://img.example.com/5.png"}
         ]}
        """,
    )

    private val tile = ApiView(
        URL,
        mapOf(
            ApiSlot.TITLE to "{race.name}",
            ApiSlot.VALUE to "{countdown(race.start)}",
            ApiSlot.LINE1 to "{race.circuit} · {race.laps} laps",
            ApiSlot.LINE2 to "{round(convert(race.length_km, 'km', 'mi'), 2)} mi a lap",
            ApiSlot.IMAGE to "{picture}",
        ),
        ApiList("standings", "{driver}", "{points} pts", "{photo}"),
    )
    private val api = TileContent.Api(
        "grand prix",
        tile,
        ApiView(slots = mapOf(ApiSlot.LINE2 to "starts {time(race.start)} · {count(standings)} drivers", ApiSlot.LINK to "https://example.com/race")),
        refreshMinutes = 15,
        liveSeconds = 15,
    )
    private val loaded = CachedApi("k", doc, doc.toString(), fetchedAt = NOW - 4 * 60_000L)

    private class Item(val x: Int, val y: Int, val size: TileSize, val view: ApiView, val cached: CachedApi?, val unlocked: Boolean = true)

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xxhdpi")
    fun faces() {
        val noPicture = tile.copy(slots = tile.slots - ApiSlot.IMAGE)
        val failed = CachedApi("k", failedAt = NOW, error = ApiProblem.OFFLINE)
        val stale = loaded.copy(failedAt = NOW, error = ApiProblem.OFFLINE)
        captureRoboImage("src/test/screenshots/api_tiles.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 52.dp)) {
                        val gap = 4.dp
                        val c = (maxWidth + gap) / 4
                        val m = TileMetrics(c, LocalDensity.current)
                        listOf(
                            Item(0, 0, TileSize.WIDE, tile, loaded),
                            Item(0, 2, TileSize.MEDIUM, tile, loaded),
                            Item(2, 2, TileSize.MEDIUM, noPicture, loaded),
                            Item(0, 4, TileSize.LARGE, noPicture, loaded),
                            Item(0, 8, TileSize.MEDIUM, tile, null),
                            Item(2, 8, TileSize.MEDIUM, tile, failed),
                            Item(0, 10, TileSize.MEDIUM, tile, null, unlocked = false),
                            Item(2, 10, TileSize.MEDIUM, noPicture, stale),
                            Item(2, 12, TileSize.SMALL, tile, loaded),
                            Item(3, 12, TileSize.SMALL, tile, null),
                        ).forEach { item ->
                            Box(Modifier.offset(c * item.x, c * item.y).size(c * item.size.width - gap, c * item.size.height - gap)) {
                                ApiFace(item.size, "grand prix", item.view, item.cached, item.unlocked, m, NOW) { _, _, _ -> picture().asImageBitmap() }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun expandedView() {
        captureRoboImage("src/test/screenshots/api_tile_expanded.png", roborazziOptions = ScreenshotOptions) {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background).padding(16.dp)) {
                    ApiDetailBody(api, loaded, NOW, live = true, paused = false) { _, _, _ -> picture().asImageBitmap() }
                }
            }
        }
    }

    @Test
    fun editor() {
        // No pictures here, so nothing is downloaded; times are left out, since the editor shows the real clock.
        val sample = JSONObject(
            """{"name": "pikachu", "id": 25, "height": 4, "weight": 60, "base_experience": 112,
                "types": [{"slot": 1, "type": {"name": "electric"}}],
                "stats": [{"base_stat": 35, "stat": {"name": "hp"}}, {"base_stat": 55, "stat": {"name": "attack"}}, {"base_stat": 40, "stat": {"name": "defense"}}]}""",
        )
        val initial = TileContent.Api(
            "pikachu",
            ApiView(
                "https://pokeapi.co/api/v2/pokemon/pikachu",
                mapOf(
                    ApiSlot.TITLE to "{title(name)}",
                    ApiSlot.VALUE to "#{id}",
                    ApiSlot.LINE1 to "{join(types, ', ', 'type.name')}",
                    ApiSlot.LINE2 to "{weight / 10} kg · {height * 10} cm",
                ),
                ApiList("stats", "{stat.name}", "{base_stat}"),
            ),
        )
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    ApiEditor(initial, onDone = {}, onCancel = {}, sample = sample)
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_tile_editor.png", roborazziOptions = ScreenshotOptions)
    }

    private val pokemon by lazy { ApiJson.parse(javaClass.getResource("/api/pokemon-shaped.json")!!.readText()) }

    private fun showPicker() {
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    ApiEditor(null, onDone = {}, onCancel = {}, sample = pokemon)
                }
            }
        }
        rule.waitForIdle()
    }

    /** A large answer starts as its top level; one object and one list opened. */
    @Test
    fun pickerTree() {
        showPicker()
        rule.onNodeWithContentDescription("sprites, { 10 }").performScrollTo().performClick()
        rule.onNodeWithContentDescription("stats, [ 6 ]").performScrollTo().performClick()
        rule.onNodeWithContentDescription("abilities, [ 2 ]").performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_picker_tree.png", roborazziOptions = ScreenshotOptions)
    }

    /** Searching names and values across the whole answer. */
    @Test
    fun pickerSearch() {
        showPicker()
        rule.onNodeWithContentDescription("search fields").performScrollTo().performTextInput("front")
        rule.waitForIdle()
        // Bring the box and the first results into view together.
        val hits = rule.onAllNodes(hasContentDescription("front", substring = true)).fetchSemanticsNodes().size
        rule.onAllNodes(hasContentDescription("front", substring = true))[minOf(7, hits - 1)].performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_picker_search.png", roborazziOptions = ScreenshotOptions)
    }

    /** Using a list: rows start with its items' name and a number, and show on the medium tile too. */
    @Test
    fun pickerRows() {
        showPicker()
        rule.onNodeWithContentDescription("stats, [ 6 ]").performScrollTo()
        rule.onNode(SemanticsMatcher("shows stats as rows") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "show stats as rows" }).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("PREVIEW").performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_picker_rows.png", roborazziOptions = ScreenshotOptions)
    }

    /** Building from a prompt: what it would make, before it is used. */
    @Test
    fun builderPreview() {
        showPicker()
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("pikachu with weight, height and stats with base_stat")
        rule.onNodeWithText("build").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("discard").performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_builder_preview.png", roborazziOptions = ScreenshotOptions)
    }

    /** The expanded view's address taking a field from the tile's answer. */
    @Test
    fun addressPicker() {
        showPicker()
        rule.onAllNodesWithText("expanded view")[0].performScrollTo().performClick()
        rule.onNodeWithText("Same address as the tile").performScrollTo().performClick()
        rule.onNode(SemanticsMatcher("expand fields") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "expand fields" }).performScrollTo().performClick()
        rule.onAllNodes(hasContentDescription("species, { 2 }"))[0].performScrollTo().performClick()
        rule.onAllNodes(hasContentDescription("species.url", substring = true))[0].performScrollTo().performClick()
        rule.waitForIdle()
        // The address with the picked field, and the picker under it.
        rule.onNodeWithText("Same address as the tile").performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_address_picker.png", roborazziOptions = ScreenshotOptions)
    }

    /** An imported setup, checked against the answer: the tile it makes and what was left out. */
    @Test
    fun importPreview() {
        showPicker()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("https://pokeapi.co/api/v2/pokemon/pikachu")
        rule.onNodeWithText("import").performScrollTo().performClick()
        rule.onNodeWithContentDescription("tile setup to import").performScrollTo().performTextInput(
            """{"name": "Pikachu", "headers": ["X-Key"], "tile": {"title": "{name}", "value": "{weight}", "line1": "height {height}", "line2": "{nickname}", "list": "stats", "rowText": "{stat.name}", "rowDetail": "{base_stat}"}, "expanded": {"title": "{name}", "line1": "{base_experience} xp", "list": "abilities", "rowText": "{ability.name}"}, "colour": "red"}""",
        )
        rule.onNodeWithText("read import").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("use this").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("skipped (2):").assertExists()
        rule.onNodeWithText("use this").performScrollTo()
        rule.onRoot().captureRoboImage("src/test/screenshots/api_import_preview.png", roborazziOptions = ScreenshotOptions)
    }

    /** The on-device AI's status when the phone can fetch the model: the download is offered. */
    @Test
    fun aiDownloadOffer() {
        TileAis.override = {
            object : TileAi {
                override suspend fun state() = AiState.Downloadable
                override fun download() = emptyFlow<AiDownload>()
                override suspend fun generate(system: String, prompt: String) = ""
                override suspend fun tokenLimit(): Int? = null
                override suspend fun countTokens(system: String, prompt: String): Int? = null
                override fun close() = Unit
            }
        }
        try {
            showPicker()
            rule.onNodeWithText("build with AI").performScrollTo().performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText("download model").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("download model").performScrollTo()
            rule.onRoot().captureRoboImage("src/test/screenshots/api_ai_download.png", roborazziOptions = ScreenshotOptions)
        } finally {
            TileAis.override = null
        }
    }

    /** A drawn stand-in picture: a road curving over green. */
    private fun picture(): Bitmap {
        val w = 320
        val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFF3B7A57.toInt())
        p.color = 0xFF555B66.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = 28f
        canvas.drawArc(w * 0.1f, h * 0.2f, w * 0.9f, h * 1.4f, 200f, 140f, false, p)
        p.color = 0xFFEEEEEE.toInt(); p.strokeWidth = 3f
        canvas.drawArc(w * 0.1f, h * 0.2f, w * 0.9f, h * 1.4f, 200f, 140f, false, p)
        return bitmap
    }
}
