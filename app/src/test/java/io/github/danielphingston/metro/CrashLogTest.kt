package io.github.danielphingston.metro

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.danielphingston.metro.data.CrashContext
import io.github.danielphingston.metro.data.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneOffset

private val info = CrashContext(
    timeMillis = 1_792_927_391_000, // 2026-10-25 11:23:11 UTC
    versionName = "0.2.0",
    versionCode = 7,
    buildType = "release",
    manufacturer = "samsung",
    model = "SM-S931B",
    sdkInt = 36,
    threadName = "main",
)

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashLogTest {
    @Test
    fun reportStartsWithTimeAppDeviceAndThread() {
        val lines = CrashLog.format(info, IllegalStateException("boom"), ZoneOffset.UTC).lines()
        assertEquals("time: 2026-10-25 11:23:11 +0000", lines[0])
        assertEquals("app: 0.2.0 (7) release", lines[1])
        assertEquals("device: samsung SM-S931B, API 36", lines[2])
        assertEquals("thread: main", lines[3])
        assertEquals("java.lang.IllegalStateException: boom", lines[4])
        assertTrue(lines[5].trim().startsWith("at "))
    }

    @Test
    fun reportKeepsTheCauseChain() {
        val error = RuntimeException("outer", IllegalArgumentException("inner"))
        val report = CrashLog.format(info, error, ZoneOffset.UTC)
        assertTrue(report.contains("java.lang.RuntimeException: outer"))
        assertTrue(report.contains("Caused by: java.lang.IllegalArgumentException: inner"))
    }

    @Test
    fun timeIsWrittenInTheGivenZone() {
        val report = CrashLog.format(info, RuntimeException(), ZoneOffset.ofHours(2))
        assertEquals("time: 2026-10-25 13:23:11 +0200", report.lines()[0])
    }

    @Test
    fun hugeReportIsCut() {
        val report = CrashLog.format(info, RuntimeException("x".repeat(100_000)), ZoneOffset.UTC)
        assertEquals(CrashLog.MAX_REPORT_CHARS, report.length)
    }

    @Test
    fun newestReportComesFirstAndOnlyTheLastFewStay() {
        var text = ""
        for (n in 1..6) text = CrashLog.prepend(text, "crash $n")
        assertEquals(listOf("crash 6", "crash 5", "crash 4", "crash 3"), CrashLog.split(text))
        assertEquals(CrashLog.KEEP, CrashLog.split(text).size)
    }

    @Test
    fun prependToNothingOrDamagedTextStillWorks() {
        assertEquals(listOf("a"), CrashLog.split(CrashLog.prepend("", "a")))
        assertEquals(listOf("a"), CrashLog.split(CrashLog.prepend(CrashLog.SEPARATOR + "  \n", "a")))
        assertEquals(listOf("a", "junk"), CrashLog.split(CrashLog.prepend("junk", "a")))
        assertEquals(emptyList<String>(), CrashLog.split(""))
    }

    @Test
    fun reportsWithBlankLinesInsideSurviveASplit() {
        val report = "time: t\n\nCaused by: x"
        assertEquals(listOf(report, "old"), CrashLog.split(CrashLog.prepend("old", report)))
    }

    @Test
    fun previewCutsLongReportsAndMarksIt() {
        val report = (1..20).joinToString("\n") { "line $it" }
        assertEquals((1..3).joinToString("\n") { "line $it" } + "\n...", CrashLog.preview(report, 3))
        assertEquals("a\nb", CrashLog.preview("a\nb", 2))
        assertEquals("a\nb", CrashLog.preview("a\nb", 5))
    }

    @Test
    fun uncaughtExceptionIsWrittenThenPassedOn() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        File(context.filesDir, CrashLog.FILE).delete()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> passedOn = error }
        try {
            CrashLog.install(context)
            val error = IllegalStateException("kaboom")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)

            assertSame(error, passedOn)
            val reports = CrashLog.read(context)
            assertEquals(1, reports.size)
            assertTrue(reports[0].contains("java.lang.IllegalStateException: kaboom"))
            assertTrue(reports[0].contains("thread: ${Thread.currentThread().name}"))

            // A second crash is added in front of the first.
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), RuntimeException("again"))
            val both = CrashLog.read(context)
            assertEquals(2, both.size)
            assertTrue(both[0].contains("again"))

            CrashLog.clear(context)
            assertTrue(CrashLog.read(context).isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }

    @Test
    fun aFailingWriteNeverStopsTheCrashFromReachingTheSystem() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.filesDir, CrashLog.FILE)
        file.delete()
        file.mkdirs() // A directory where the file should be: every read and write fails.
        val original = Thread.getDefaultUncaughtExceptionHandler()
        var passedOn: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, error -> passedOn = error }
        try {
            CrashLog.install(context)
            val error = RuntimeException("still reported")
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
            assertSame(error, passedOn)
            assertTrue(CrashLog.read(context).isEmpty())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
            file.delete()
        }
    }
}
