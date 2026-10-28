package io.github.danielphingston.metro

import io.github.danielphingston.metro.data.AssistantLastReply
import io.github.danielphingston.metro.data.AssistantLastReply.Source
import io.github.danielphingston.metro.data.AssistantModel
import io.github.danielphingston.metro.data.SavedReply
import io.github.danielphingston.metro.live.AppNotifications
import io.github.danielphingston.metro.live.NotificationPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AssistantLastReplyTest {
    private val pkg = "com.anthropic.claude"
    private val utc = ZoneId.of("UTC")
    private val noon = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, utc).toInstant().toEpochMilli()
    private val saved = SavedReply(pkg, "Title", "Reply", 1L)

    private fun ago(ms: Long) = AssistantLastReply.relativeTime(noon, noon - ms, utc, Locale.US)
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun relativeTimeBuckets() {
        assertEquals("just now", ago(30_000))
        assertEquals("just now", ago(-5 * minute))
        assertEquals("1m ago", ago(minute))
        assertEquals("59m ago", ago(59 * minute))
        assertEquals("1h ago", ago(hour))
        assertEquals("11h ago", ago(11 * hour + 59 * minute))
    }

    @Test
    fun relativeTimeUsesCalendarDays() {
        // 13h before noon is 23:00 the day before: yesterday, not "13h ago".
        assertEquals("yesterday", ago(13 * hour))
        assertEquals("yesterday", ago(30 * hour))
        assertEquals("2 days ago", ago(2 * 24 * hour))
        assertEquals("6 days ago", ago(6 * 24 * hour))
        assertEquals("Oct 20", ago(8 * 24 * hour))
    }

    @Test
    fun sourcePrefersLiveThenMatchingSaved() {
        assertEquals(Source.LIVE, AssistantLastReply.source(true, saved, pkg, true))
        assertEquals(Source.SAVED, AssistantLastReply.source(false, saved, pkg, true))
        assertEquals(Source.NONE, AssistantLastReply.source(false, saved, "com.openai.chatgpt", true))
        assertEquals(Source.NONE, AssistantLastReply.source(false, saved, null, true))
        assertEquals(Source.NONE, AssistantLastReply.source(false, saved, pkg, false))
        assertEquals(Source.NONE, AssistantLastReply.source(false, null, pkg, true))
    }

    @Test
    fun toSaveRespectsShowContentAndCapsLength() {
        val preview = NotificationPreview("Claude", "x".repeat(5000), 7L)
        assertNull(AssistantLastReply.toSave(pkg, "Claude", preview, showContent = false))
        assertNull(AssistantLastReply.toSave(pkg, "Claude", null, showContent = true))
        val s = AssistantLastReply.toSave(pkg, "Claude", preview, true)!!
        assertEquals(AssistantLastReply.MAX_REPLY, s.reply.length)
        assertNull(s.title) // the title was only the app's name
        assertEquals(7L, s.postTime)
        assertNull(AssistantLastReply.toSave(pkg, "Claude", NotificationPreview("", "  ", 1L), true))
    }

    @Test
    fun writesOnlyWhenContentChanged() {
        assertTrue(AssistantLastReply.shouldWrite(null, saved))
        assertFalse(AssistantLastReply.shouldWrite(saved, saved.copy(postTime = 99L)))
        assertTrue(AssistantLastReply.shouldWrite(saved, saved.copy(reply = "New")))
        assertTrue(AssistantLastReply.shouldWrite(saved, saved.copy(packageName = "other")))
        assertFalse(AssistantLastReply.shouldWrite(saved, null))
    }

    @Test
    fun jsonRoundTrip() {
        assertEquals(saved, AssistantLastReply.fromJson(AssistantLastReply.toJson(saved)))
        val noTitle = saved.copy(title = null)
        assertEquals(noTitle, AssistantLastReply.fromJson(AssistantLastReply.toJson(noTitle)))
        assertNull(AssistantLastReply.fromJson("not json"))
    }

    @Test
    fun modelShowsSavedAsReadWhenNoLiveContent() {
        val m = AssistantModel.of("Claude", null, true, null, saved, pkg)
        assertTrue(m.isRead)
        assertEquals("Reply", m.reply)
        assertEquals(0, m.count)
        assertEquals(1L, m.readAt)
        assertFalse(AssistantModel.of("Claude", null, true, null, saved, "other").hasReply)
        assertFalse(AssistantModel.of("Claude", null, false, null, saved, pkg).hasReply)
    }

    @Test
    fun askedPairsOnlyWithALaterReply() {
        assertTrue(AssistantLastReply.pairsWithAsked(replyTime = 100L, askedAt = 50L))
        assertFalse(AssistantLastReply.pairsWithAsked(replyTime = 100L, askedAt = 100L))
        assertFalse(AssistantLastReply.pairsWithAsked(replyTime = 100L, askedAt = 150L))
        val asked = io.github.danielphingston.metro.data.LastAsked("q", 50L)
        assertEquals(asked, AssistantModel.of("Claude", null, true, asked, saved.copy(postTime = 100L), pkg).footerAsked)
        assertNull(AssistantModel.of("Claude", null, true, asked.copy(at = 150L), saved.copy(postTime = 100L), pkg).footerAsked)
        // Live content keeps showing the question as before.
        val live = AppNotifications(1, listOf(NotificationPreview("Chat", "Hello", 5L)))
        assertEquals(asked, AssistantModel.of("Claude", live, true, asked).footerAsked)
    }

    @Test
    fun liveWinsOverSaved() {
        val live = AppNotifications(2, listOf(NotificationPreview("Chat", "Hello", 5L)))
        val m = AssistantModel.of("Claude", live, true, null, saved, pkg)
        assertFalse(m.isRead)
        assertEquals("Hello", m.reply)
        assertEquals(2, m.count)
    }
}
