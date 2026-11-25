package io.github.danielphingston.metro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import io.github.danielphingston.metro.data.LauncherSettings
import io.github.danielphingston.metro.data.SettingsRepository
import io.github.danielphingston.metro.data.StartBackground
import io.github.danielphingston.metro.ui.settings.SettingsIndex
import io.github.danielphingston.metro.ui.settings.SettingsPage
import io.github.danielphingston.metro.ui.settings.SettingsScreen
import io.github.danielphingston.metro.ui.theme.MetroTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = MetroApplication::class)
class SettingsSearchTest {
    @get:Rule
    val compose = createComposeRule()

    private fun titles(query: String) = SettingsIndex.search(query).map { it.title }

    @Test
    fun findsSettingsByTheWordsPeopleUse() {
        assertEquals("start background", titles("wallpaper").first())
        assertEquals("theme", titles("dark").first())
        assertEquals("tiles per row", titles("tiles per").first())
        assertEquals("tiles per row", titles("columns").first())
        // American spelling finds the British one.
        assertTrue("accent colour" in titles("accent color"))
        // Every word must match; a title that starts with the query comes first.
        assertEquals(listOf("tile transparency"), titles("tile transp"))
        assertEquals("export backup", titles("backup").first())
        assertTrue(titles("").isEmpty() && titles("   ").isEmpty() && titles("zzzz").isEmpty())
    }

    @Test
    fun anchorsAreUniqueAndEveryPageIsKnown() {
        val anchors = SettingsIndex.entries.mapNotNull { it.anchor }
        assertEquals(anchors.size, anchors.toSet().size)
    }

    /** Renders each page and compares the anchors on it with the index: neither may drift from the other. */
    @Test
    fun everyIndexedSettingIsOnItsPageAndEveryAnchorIsIndexed() {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        var page by mutableStateOf(SettingsPage.START)
        // A photo background shows the photo options too.
        val settings = LauncherSettings(background = StartBackground.PHOTO_TILES, backgroundPhoto = "content://p")
        compose.setContent {
            MetroTheme(settings) {
                androidx.compose.runtime.key(page) { SettingsScreen(settings, repository, page) }
            }
        }
        val tagged = SemanticsMatcher("has a setting anchor") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("setting:") == true
        }
        for (current in SettingsPage.entries) {
            page = current
            compose.waitForIdle()
            val found = compose.onAllNodes(tagged, useUnmergedTree = true).fetchSemanticsNodes()
                .map { it.config[SemanticsProperties.TestTag].removePrefix("setting:") }.toSet()
            val indexed = SettingsIndex.entries.filter { it.page == current && it.anchor != null }
            val unknown = found - indexed.mapNotNull { it.anchor }.toSet()
            assertTrue("$current has anchors the index doesn't list: $unknown", unknown.isEmpty())
            val missing = indexed.filter { it.alwaysShown && it.shownWhen(settings) && it.anchor !in found }.map { it.anchor }
            assertTrue("$current doesn't show indexed settings: $missing", missing.isEmpty())
        }
    }

    @Test
    fun aResultOpensItsPageAndShowsTheSetting() {
        val settings = LauncherSettings()
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        compose.setContent { MetroTheme(settings) { SettingsScreen(settings, repository) } }
        compose.onNode(hasSetTextAction()).performTextInput("swing")
        compose.onNodeWithText("swing pages").performClick()
        compose.waitForIdle()
        // The animations page, scrolled to its last section.
        compose.onNodeWithTag("setting:swing_pages", useUnmergedTree = true).assertIsDisplayed()
    }
}
