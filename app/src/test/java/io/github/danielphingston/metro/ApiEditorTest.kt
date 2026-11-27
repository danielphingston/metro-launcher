package io.github.danielphingston.metro

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.danielphingston.metro.data.ApiList
import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.ThemeMode
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.live.api.ApiJson
import io.github.danielphingston.metro.live.api.ApiSecrets
import io.github.danielphingston.metro.live.api.InMemoryApiSecrets
import io.github.danielphingston.metro.ui.theme.Metro
import io.github.danielphingston.metro.ui.theme.MetroTheme
import io.github.danielphingston.metro.ui.tiles.ApiEditor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Setting up an API tile by hand, with a large answer shaped like PokéAPI's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = android.app.Application::class)
class ApiEditorTest {
    @get:Rule
    val rule = createComposeRule()

    private val pokemon = ApiJson.parse(javaClass.getResource("/api/pokemon-shaped.json")!!.readText())

    @Before
    fun secrets() {
        ApiSecrets.override = InMemoryApiSecrets()
    }

    @After
    fun restore() {
        ApiSecrets.override = null
    }

    private var saved: TileContent.Api? = null

    private fun show(sample: Any? = pokemon) {
        rule.setContent {
            MetroTheme(LauncherSettings(theme = ThemeMode.DARK)) {
                Box(Modifier.fillMaxSize().background(Metro.colors.background)) {
                    ApiEditor(null, onDone = { saved = it }, onCancel = {}, sample = sample)
                }
            }
        }
        // A new tile opens on the import: the address is set by hand under advanced.
        rule.onNodeWithText("advanced").performClick()
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("https://pokeapi.co/api/v2/pokemon/pikachu")
    }

    private fun chip(label: String) = rule.onAllNodesWithText(label).onFirst().performScrollTo().performClick()

    private fun add() {
        rule.onNodeWithText("add tile").performClick()
        rule.waitUntil(5_000) { saved != null }
    }

    @Test
    fun whyItCantBeSavedShowsNextToTheButton() {
        show()
        // Nothing picked yet: the button stays on screen, and says why right above it.
        rule.onNodeWithText("add tile").assertIsDisplayed().performClick()
        rule.onNodeWithText("pick at least one field for the tile to show").assertIsDisplayed()
        assertNull(saved)
    }

    @Test
    fun aListBecomesRowsThatShowAtOnce() {
        show()
        chip("list")
        // Only lists are offered, and the big answer starts closed: stats is right there.
        rule.onNodeWithContentDescription("stats", substring = true).performScrollTo().performClick()
        add()
        assertEquals(ApiList("stats", "{stat.name}", "{base_stat}"), saved!!.tile.list)
    }

    @Test
    fun searchFindsAFieldDeepInTheAnswer() {
        show()
        chip("picture")
        rule.onNodeWithContentDescription("search fields").performScrollTo().performTextInput("official")
        rule.onNodeWithText("sprites.other['official-artwork'].front_default").performScrollTo().performClick()
        chip("title")
        // The search keeps its text after a pick, so several fields can be added in a row.
        rule.onNodeWithContentDescription("search fields").performScrollTo().performTextReplacement("name")
        // Exact key matches rank first, so the top-level name leads the results.
        rule.onAllNodes(hasContentDescription("name, ", substring = true))[0].performScrollTo().performClick()
        add()
        assertEquals("{sprites.other['official-artwork'].front_default}", saved!!.tile.slot(ApiSlot.IMAGE))
        assertEquals("{name}", saved!!.tile.slot(ApiSlot.TITLE))
    }

    @Test
    fun theTreeOpensOneLevelAtATime() {
        show()
        chip("title")
        // Inside an object only after opening it.
        assertEquals(0, rule.onAllNodesWithText("front_default").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("sprites, { 10 }").performScrollTo().performClick()
        rule.onAllNodesWithText("front_default").onFirst().performScrollTo().performClick()
        add()
        assertEquals("{sprites.front_default}", saved!!.tile.slot(ApiSlot.TITLE))
    }

    @Test
    fun theBackHasItsOwnTabAndAddressAndNoLink() {
        show()
        // The front's big value, then the back's, each in the template field (name, address, prompt, template).
        rule.onAllNodes(hasSetTextAction())[3].performScrollTo().performTextInput("{name}")
        rule.onNodeWithText("link").assertExists()
        chip("back")
        rule.onNodeWithText("What the tile flips to now and then", substring = true).assertExists()
        // A tap on the back does what the front does: no link of its own.
        rule.onNodeWithText("link").assertDoesNotExist()
        rule.onAllNodes(hasSetTextAction())[3].performScrollTo().performTextInput("{weight} kg")
        // Its own address, built from the tile's answer, comes before the prompt.
        rule.onNodeWithText("Same address as the tile").performScrollTo().performClick()
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput("{species.url}")
        add()
        val tile = saved!!
        assertEquals("{name}", tile.tile.slot(ApiSlot.VALUE))
        assertEquals("{weight} kg", tile.back.slot(ApiSlot.VALUE))
        assertEquals("{species.url}", tile.back.url)
        assertTrue(tile.hasBack)
        assertEquals("", tile.detail.url)
    }

    @Test
    fun aTileWithoutABackDoesntFlip() {
        show()
        rule.onAllNodes(hasSetTextAction())[3].performScrollTo().performTextInput("{name}")
        add()
        assertFalse(saved!!.hasBack)
        assertEquals(io.github.danielphingston.metro.data.ApiView(), saved!!.back)
    }

    @Test
    fun addTileSavesTheTile() {
        show()
        rule.onAllNodesWithText("title").onFirst().performScrollTo().performClick()
        // Fields in order: name, address, build prompt, then the template.
        rule.onAllNodes(hasSetTextAction())[3].performScrollTo().performTextInput("{name}")
        add()
        assertNotNull(saved)
        assertEquals("{name}", saved!!.tile.slots.values.single())
    }

    private fun build(prompt: String) {
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextInput(prompt)
        rule.onNodeWithText("build").performScrollTo().performClick()
    }

    @Test
    fun aBuildFillsTheTileOnlyAfterUseThis() {
        show()
        build("weight and height and stats")
        rule.onNodeWithText("no field for", substring = true).assertDoesNotExist()
        // Shown first: nothing changes until it is used.
        rule.onNodeWithText("add tile").performClick()
        rule.onNodeWithText("pick at least one field for the tile to show").assertIsDisplayed()
        rule.onNodeWithText("use this").performScrollTo().performClick()
        add()
        assertEquals("{weight}", saved!!.tile.slot(ApiSlot.VALUE))
        assertEquals("height {height}", saved!!.tile.slot(ApiSlot.LINE1))
        assertEquals("stats", saved!!.tile.list!!.path)
    }

    @Test
    fun discardLeavesTheFieldsAsTheyWere() {
        show()
        build("")
        rule.onNodeWithText("discard").performScrollTo().performClick()
        rule.onNodeWithText("use this").assertDoesNotExist()
        rule.onNodeWithText("add tile").performClick()
        rule.onNodeWithText("pick at least one field for the tile to show").assertIsDisplayed()
        assertNull(saved)
    }

    @Test
    fun aBuildSaysWhatItCouldntMatchAndNeedsAnAnswer() {
        show()
        build("weight zebra")
        rule.onNodeWithText("no field for: zebra").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aBuildBeforeAnyAnswerSaysToFetchFirst() {
        show(sample = null)
        build("weight")
        rule.onNodeWithText("fetch the address first: the build reads its answer").performScrollTo().assertIsDisplayed()
    }
}
