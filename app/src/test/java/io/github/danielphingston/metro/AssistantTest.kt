package io.github.danielphingston.metro

import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Process
import io.github.danielphingston.metro.data.AppInfo
import io.github.danielphingston.metro.data.AppKey
import io.github.danielphingston.metro.data.AssistantApps
import io.github.danielphingston.metro.data.AssistantChoice
import io.github.danielphingston.metro.data.AssistantIntents
import io.github.danielphingston.metro.data.AssistantModel
import io.github.danielphingston.metro.data.AssistantText
import io.github.danielphingston.metro.data.LastAsked
import io.github.danielphingston.metro.data.TileContent
import io.github.danielphingston.metro.data.TileJson
import io.github.danielphingston.metro.data.TileSize
import io.github.danielphingston.metro.data.SearchEngine
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.NotificationPreview
import io.github.danielphingston.metro.ui.search.AssistantTarget
import io.github.danielphingston.metro.ui.search.SearchRow
import io.github.danielphingston.metro.ui.search.searchRows
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AssistantTest {
    private val claude = "com.anthropic.claude"
    private val chatgpt = "com.openai.chatgpt"

    @Test
    fun defaultIsClaudeThenFirstInstalledKnownThenOff() {
        assertEquals(claude, AssistantChoice.defaultPackage(setOf(chatgpt, claude, "com.other")))
        assertEquals(chatgpt, AssistantChoice.defaultPackage(setOf("com.google.android.apps.bard", chatgpt)))
        assertNull(AssistantChoice.defaultPackage(setOf("com.other")))
    }

    @Test
    fun theDefaultAppliesOnlyWhileTheUserNeverChose() {
        val installed = setOf(claude, chatgpt)
        assertEquals(claude, AssistantChoice.resolve(null, installed))
        assertNull(AssistantChoice.resolve(AssistantChoice.OFF, installed))
        assertEquals(chatgpt, AssistantChoice.resolve(chatgpt, installed))
        assertEquals("com.other", AssistantChoice.resolve("com.other", installed))
    }

    @Test
    fun knownAssistantsKeepTheirOrderAndNames() {
        assertEquals(listOf("Claude", "ChatGPT"), AssistantChoice.installedKnown(setOf(chatgpt, claude)).map { it.label })
        assertEquals("Claude", AssistantChoice.displayName(claude, "x"))
        assertEquals("Foo", AssistantChoice.displayName("com.foo", "Foo"))
    }

    @Test
    fun planPrefersSendThenCopyAndOpen() {
        assertEquals(AssistantIntents.Plan.SEND, AssistantIntents.plan(canSend = true, canLaunch = true))
        assertEquals(AssistantIntents.Plan.COPY_AND_OPEN, AssistantIntents.plan(canSend = false, canLaunch = true))
        assertEquals(AssistantIntents.Plan.NONE, AssistantIntents.plan(canSend = false, canLaunch = false))
    }

    @Test
    fun sendIntentIsPlainTextAimedAtOneApp() {
        val intent = AssistantIntents.send(claude, "why is the sky blue")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals(claude, intent.`package`)
        assertEquals("why is the sky blue", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun storedQuestionsAreTrimmedAndBounded() {
        assertEquals("hi", AssistantIntents.storable("  hi \n"))
        assertEquals(AssistantIntents.MAX_STORED, AssistantIntents.storable("x".repeat(1000)).length)
    }

    private fun app(pkg: String, label: String, serial: Long = 0) = AppInfo(
        AppKey(pkg, "Main", serial), label, Process.myUserHandle(), ComponentName(pkg, "Main"), true, 0,
    ) { _, _ -> ColorDrawable(0) }

    @Test
    fun otherTargetsAreLaunchableTextAcceptingAppsWithoutKnownOnesOrSelf() {
        val apps = listOf(app("com.b", "Bee"), app(claude, "Claude"), app("com.a", "Ant"), app("com.no", "No"), app("com.me", "Metro"), app("com.w", "Work", 10))
        val result = AssistantApps.otherTargets(setOf("com.a", "com.b", claude, "com.me", "com.w"), apps, "com.me")
        assertEquals(listOf("Ant", "Bee"), result.map { it.label })
    }

    // --- Notification text extraction ---

    @Test
    fun claudeStyleTitleIsAConversationTitle() {
        val e = AssistantText.extract("Claude", "Pull down search refinements", "Sure. Here is a plan.")
        assertEquals("Pull down search refinements", e.title)
        assertEquals("Sure. Here is a plan.", e.reply)
    }

    @Test
    fun titleEqualToTheAppNameIsDropped() {
        val e = AssistantText.extract("ChatGPT", "chatgpt", "Here is your answer")
        assertNull(e.title)
        assertEquals("Here is your answer", e.reply)
        assertNull(AssistantText.extract("ChatGPT", "", "text").title)
    }

    @Test
    fun appNamePrefixesAreStripped() {
        val e = AssistantText.extract("Claude", "Claude: Trip ideas", "Claude - Try Lisbon.")
        assertEquals("Trip ideas", e.title)
        assertEquals("Try Lisbon.", e.reply)
        // Not a separator: left alone.
        assertEquals("Claude-3 is out", AssistantText.extract("Claude", "x", "Claude-3 is out").reply)
        assertNull(AssistantText.extract("Claude", "Claude:", "").title)
    }

    @Test
    fun emptyTextLeavesOnlyTheTitle() {
        val e = AssistantText.extract("Gemini", "Recipe ideas", "")
        assertEquals("Recipe ideas", e.title)
        assertEquals("", e.reply)
    }

    private fun data(title: String, text: String, count: Int = 2) =
        AppNotifications(count, listOf(NotificationPreview(title, text, 1L)))

    @Test
    fun modelUsesTheNewestPreviewAndRespectsTheContentSetting() {
        val asked = LastAsked("q", 5)
        val shown = AssistantModel.of("Claude", data("Title", "Reply"), true, asked)
        assertEquals("Title", shown.title)
        assertEquals("Reply", shown.reply)
        assertEquals(2, shown.count)
        assertTrue(shown.hasBack(TileSize.WIDE))
        assertFalse(shown.hasBack(TileSize.SMALL))
        // Content hidden by the user: a count only, nothing to flip away from.
        val hidden = AssistantModel.of("Claude", data("Title", "Reply"), false, asked)
        assertFalse(hidden.hasReply)
        assertFalse(hidden.hasBack(TileSize.WIDE))
        assertEquals("Claude, 2 unread", hidden.description())
    }

    @Test
    fun descriptionSummarisesAppCountAndLatestTitle() {
        val model = AssistantModel.of("Claude", data("Pull down search refinements", "Sure."), true, null)
        assertEquals("Claude, 2 unread, latest: Pull down search refinements", model.description())
        assertEquals("Claude", AssistantModel.of("Claude", null, true, null).description())
    }

    @Test
    fun noFlipWithoutALastQuestion() {
        assertFalse(AssistantModel.of("Claude", data("T", "R"), true, null).hasBack(TileSize.LARGE))
    }

    // --- Persistence and search row ---

    @Test
    fun assistantTileRoundTripsAndUnknownTypesAreCarried() {
        val tiles = listOf(io.github.danielphingston.metro.data.TileSpec(1, TileSize.WIDE, TileContent.Assistant))
        assertEquals(tiles, TileJson.decode(JSONArray(TileJson.encode(tiles).toString())))
        val saved = """[{"id":1,"type":"assistant","size":"WIDE"},{"id":2,"type":"hologram","size":"WIDE"}]"""
        val read = TileJson.read(saved)!!
        assertEquals(1, read.tiles.size)
        assertEquals(2, TileJson.encode(read.tiles, read.unread).length())
    }

    @Test
    fun searchRowAsksTheAssistantAfterTheWebRowOnlyWithAQuery() {
        val target = AssistantTarget(app(claude, "Claude"), "Claude")
        val rows = searchRows(emptyList(), "why", emptyList(), SearchEngine.GOOGLE, 0L, target)
        assertTrue(rows.last() is SearchRow.Assistant)
        assertTrue(rows[rows.size - 2] is SearchRow.Web)
        assertTrue(searchRows(emptyList(), "", emptyList(), SearchEngine.GOOGLE, 0L, target).none { it is SearchRow.Assistant })
        assertTrue(searchRows(emptyList(), "why", emptyList(), SearchEngine.GOOGLE, 0L, null).none { it is SearchRow.Assistant })
    }
}
