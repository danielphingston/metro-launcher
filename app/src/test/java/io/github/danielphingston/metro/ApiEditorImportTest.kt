package io.github.danielphingston.metro

import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiSecrets
import io.github.danielphingston.metro.live.api.InMemoryApiSecrets
import io.github.danielphingston.metro.ui.ShareTileActivity
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.AiDownload
import io.github.danielphingston.metro.ui.tiles.AiState
import io.github.danielphingston.metro.ui.tiles.ApiEditor
import io.github.danielphingston.metro.ui.tiles.ApiImport
import io.github.danielphingston.metro.ui.tiles.TileAi
import io.github.danielphingston.metro.ui.tiles.TileAiException
import io.github.danielphingston.metro.ui.tiles.TileAis
import io.github.danielphingston.metro.ui.tiles.TileImports
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The editor's on-device AI, JSON import and copied prompt, with a stand-in model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ApiEditorImportTest {
    @get:Rule
    val rule = createComposeRule()

    private val pokemon = ApiJson.parse(javaClass.getResource("/api/pokemon-shaped.json")!!.readText())
    private val url = "https://pokeapi.co/api/v2/pokemon/pikachu"
    private var saved: TileContent.Api? = null
    private var open by mutableStateOf(true)

    /** A stand-in for Gemini Nano. */
    private class FakeAi(
        var state: AiState = AiState.Ready,
        var answer: () -> String = { "" },
        val downloads: List<AiDownload> = listOf(AiDownload.Started(2_000_000_000), AiDownload.Done),
    ) : TileAi {
        var opened = 0
        var closed = false
        var asked: String? = null
        override suspend fun state() = state
        override fun download(): Flow<AiDownload> = flowOf(*downloads.toTypedArray()).also { state = AiState.Ready }
        override suspend fun generate(system: String, prompt: String): String {
            asked = prompt
            return answer()
        }
        override suspend fun tokenLimit(): Int? = null
        override suspend fun countTokens(system: String, prompt: String): Int? = null
        override fun close() {
            closed = true
        }
    }

    private val ai = FakeAi()

    @Before
    fun setUp() {
        ApiSecrets.override = InMemoryApiSecrets()
        TileAis.override = { ai.opened++; ai }
    }

    @After
    fun restore() {
        ApiSecrets.override = null
        TileAis.override = null
        TileImports.clear()
    }

    private fun show(sample: Any? = pokemon, initialImport: String? = null) {
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    if (open) ApiEditor(null, onDone = { saved = it }, onCancel = {}, sample = sample, initialImport = initialImport)
                }
            }
        }
        // A new tile opens on the import: the address is set by hand under advanced.
        rule.onNodeWithText("advanced").performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput(url)
    }

    private fun tap(label: String) = rule.onNodeWithText(label).performScrollTo().performClick()

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) { rule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun add() {
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
    }

    private fun paste(text: String) {
        if (rule.onAllNodes(hasContentDescription("tile setup to import")).fetchSemanticsNodes().isEmpty()) rule.onNodeWithText("import").performClick()
        rule.onNodeWithContentDescription("tile setup to import").performScrollTo().performTextReplacement(text)
        tap("read import")
    }

    @Test
    fun anImportIsCheckedAndOnlyUsedOnUseThis() {
        show()
        paste("""```json
            {"name": "Pika", "headers": {"X-Key": "secret"}, "tile": {"title": "{name}", "value": "{weight}", "line1": "{zebra}", "list": "stats", "rowText": "{stat.name}"}}
            ```""")
        waitFor("use this")
        rule.onNodeWithText("the answer has no zebra", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("values aren't imported", substring = true).performScrollTo().assertIsDisplayed()
        tap("use this")
        // The header comes as a name waiting for its value, asked for right on the import page; an empty one isn't saved.
        rule.onNodeWithText("KEYS IT NEEDS").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("YOUR TILE").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("add tile").performClick()
        rule.onNodeWithText("X-Key needs a value").assertIsDisplayed()
        assertNull(saved)
        // The import box, then the key's value.
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("my-key")
        add()
        val tile = saved!!
        assertEquals("Pika", tile.name)
        assertEquals(url, tile.tile.url)
        assertEquals("{name}", tile.tile.slot(ApiSlot.TITLE))
        assertEquals("{weight}", tile.tile.slot(ApiSlot.VALUE))
        assertEquals("", tile.tile.slot(ApiSlot.LINE1))
        assertEquals("stats", tile.tile.list!!.path)
    }

    @Test
    fun aBrokenImportSaysWhyAndChangesNothing() {
        show()
        paste("""{"tile": {"title": "{name}" """)
        waitFor("that isn't valid JSON", substring = true)
        rule.onNodeWithText("use this").assertDoesNotExist()
        paste("""{"tile": {"value": "{nope}"}}""")
        waitFor("nothing in it works with this answer", substring = true)
        rule.onNodeWithText("add tile").performClick()
        rule.onNodeWithText("pick at least one field for the tile to show").assertIsDisplayed()
        assertNull(saved)
    }

    @Test
    fun aSharedSetupWaitsInTheBoxUntilRead() {
        show(initialImport = """{"tile": {"title": "{name}"}}""")
        rule.onNodeWithText("import").performClick()
        rule.onNodeWithContentDescription("tile setup to import").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("use this").assertDoesNotExist()
        tap("read import")
        waitFor("use this")
        tap("use this")
        add()
        assertEquals("{name}", saved!!.tile.slot(ApiSlot.TITLE))
    }

    @Test
    fun copyPromptPutsTheFormatTheAddressAndTheFieldsOnTheClipboard() {
        show()
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("name and weight")
        // The prompt is typed under advanced; copy prompt sits with the import.
        rule.onNodeWithText("import").performClick()
        tap("copy prompt")
        waitFor("prompt copied", substring = true)
        val clip = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java).primaryClip!!
        val text = clip.getItemAt(0).text.toString()
        assertEquals(ApiImport.prompt(url, "name and weight", pokemon), text)
        assertTrue(text.contains(ApiImport.FORMAT))
    }

    @Test
    fun theAiFillsTheTileButNeverItsAddressOrHeaders() {
        ai.answer = { """{"name": "Pikachu", "url": "https://evil.example.com/x", "headers": ["X-Steal"], "tile": {"title": "{name}", "value": "{weight}", "line1": "{made_up.field}"}}""" }
        show()
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("name and weight")
        tap("build with AI")
        waitFor("written by the on-device AI", substring = true)
        rule.onNodeWithText("the answer has no made_up.field", substring = true).performScrollTo().assertIsDisplayed()
        assertTrue(ai.asked!!.contains("weight = 60"))
        assertTrue(ai.asked!!.endsWith("name and weight"))
        tap("use this")
        add()
        val tile = saved!!
        assertEquals(url, tile.tile.url)
        assertTrue(tile.headers.isEmpty())
        assertEquals("{weight}", tile.tile.slot(ApiSlot.VALUE))
        assertEquals("", tile.tile.slot(ApiSlot.LINE1))
    }

    @Test
    fun theModelIsOnlyOpenedOnTapAndClosedWithTheEditor() {
        ai.answer = { """{"tile": {"title": "{name}"}}""" }
        show()
        assertEquals(0, ai.opened)
        tap("build with AI")
        waitFor("use this")
        assertEquals(1, ai.opened)
        open = false
        rule.waitForIdle()
        assertTrue(ai.closed)
    }

    @Test
    fun aModelToDownloadIsOfferedAndThenUsed() {
        ai.state = AiState.Downloadable
        ai.answer = { """{"tile": {"title": "{name}"}}""" }
        show()
        tap("build with AI")
        waitFor("download model")
        rule.onNodeWithText("isn't on this phone yet", substring = true).assertIsDisplayed()
        tap("download model")
        waitFor("on-device AI: ready")
        tap("build with AI")
        waitFor("use this")
    }

    @Test
    fun aFailedDownloadSaysWhy() {
        val failing = FakeAi(state = AiState.Downloadable, downloads = listOf(AiDownload.Failed("not enough free space for the model")))
        TileAis.override = { failing }
        show()
        tap("build with AI")
        tap("download model")
        waitFor("on-device AI: not enough free space for the model")
    }

    @Test
    fun aPhoneWithoutTheModelSaysSoAndTheRestStillWorks() {
        ai.state = AiState.Unavailable("this phone doesn't offer it (code 8)")
        show()
        tap("build with AI")
        waitFor("on-device AI isn't available: this phone doesn't offer it (code 8). Build, import and copy prompt still work.")
        rule.onNodeWithText("download model").assertDoesNotExist()
    }

    @Test
    fun aModelThatFailsOrAnswersNonsenseNeverCrashes() {
        show()
        ai.answer = { throw TileAiException("the model is busy: try again in a moment") }
        tap("build with AI")
        waitFor("on-device AI: the model is busy: try again in a moment")
        ai.answer = { throw IllegalStateException("boom") }
        tap("build with AI")
        waitFor("on-device AI: the model failed (IllegalStateException)")
        ai.answer = { "I'm sorry, I can't help with that." }
        tap("build with AI")
        waitFor("the model's answer wasn't a tile setup", substring = true)
        ai.answer = { """{"tile": {"title": "{nothing.here}"}}""" }
        tap("build with AI")
        waitFor("nothing the model wrote works with this answer", substring = true)
        assertFalse(rule.onAllNodesWithText("use this").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theAiNeedsTheAnswerFirst() {
        show(sample = null)
        tap("build with AI")
        waitFor("on-device AI: fetch the address first: the AI reads its answer")
        assertEquals(0, ai.opened)
    }

    @Test
    fun theShareSheetOffersImportAndMlKitDoesNotStartWithTheLauncher() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = context.packageManager
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        val found = pm.queryIntentActivities(share, PackageManager.MATCH_ALL).map { it.activityInfo.name }
        assertTrue(found.toString(), ShareTileActivity::class.java.name in found)
        val providers = pm.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty().map { it.name }
        assertTrue(providers.toString(), providers.isNotEmpty())
        assertFalse(providers.toString(), "com.google.mlkit.common.internal.MlKitInitProvider" in providers)
    }

    @Test
    fun sharedTextIsKeptOnlyWhenThereIsSomeAndCutToSize() {
        assertFalse(TileImports.offer(null))
        assertFalse(TileImports.offer("   "))
        assertNull(TileImports.pending.value)
        assertTrue(TileImports.offer("x".repeat(ApiImport.MAX_CHARS + 10)))
        assertEquals(ApiImport.MAX_CHARS, TileImports.pending.value!!.length)
    }
}
