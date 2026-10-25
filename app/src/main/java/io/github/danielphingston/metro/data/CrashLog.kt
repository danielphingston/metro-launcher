package io.github.danielphingston.metro.data

import android.content.Context
import android.os.Build
import io.github.danielphingston.metro.BuildConfig
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a crash report says about the app and phone, besides the stack trace. */
data class CrashContext(
    val timeMillis: Long,
    val versionName: String,
    val versionCode: Int,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val threadName: String,
)

/**
 * A local record of the last few crashes, for finding out why the launcher stops. Reports are
 * plain text in [FILE] in the app's private storage, newest first. Nothing is sent anywhere: the
 * only way out is the "share" button in Metro settings.
 *
 * The formatting and trimming are pure functions so they can be unit tested; only [install] and
 * the file helpers touch Android.
 */
object CrashLog {
    const val FILE = "last_crash.txt"

    /** The newest crash plus the three before it. */
    const val KEEP = 4

    /** A report is cut to this many characters, so a huge exception message can't fill the disk. */
    const val MAX_REPORT_CHARS = 24_000

    /** Between two reports in the file. Never part of a report's own text in practice. */
    const val SEPARATOR = "\n=====\n"

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.US)

    /**
     * One report: a header (time, app, device, thread) followed by the full stack trace of
     * [error] with its causes. [zone] only affects how the time is written.
     */
    fun format(info: CrashContext, error: Throwable, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = timeFormat.format(Instant.ofEpochMilli(info.timeMillis).atZone(zone))
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trimEnd()
        val report = buildString {
            appendLine("time: $time")
            appendLine("app: ${info.versionName} (${info.versionCode}) ${info.buildType}")
            appendLine("device: ${info.manufacturer} ${info.model}, API ${info.sdkInt}")
            appendLine("thread: ${info.threadName}")
            append(trace)
        }
        return report.take(MAX_REPORT_CHARS)
    }

    /** The file's text with [report] first, keeping at most [keep] reports. [existing] may be empty or damaged. */
    fun prepend(existing: String, report: String, keep: Int = KEEP): String =
        (listOf(report) + split(existing)).take(keep).joinToString(SEPARATOR)

    /** The reports in [text], newest first; blank parts are dropped. */
    fun split(text: String): List<String> = text.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** The first [maxLines] lines of [report], with a trailing "..." line when there are more. */
    fun preview(report: String, maxLines: Int): String {
        val lines = report.lines()
        return if (lines.size <= maxLines) report else lines.take(maxLines).joinToString("\n") + "\n..."
    }

    /** The recorded reports, newest first; empty if there is no file or it can't be read. */
    fun read(context: Context): List<String> =
        try {
            File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.let(::split).orEmpty()
        } catch (_: IOException) {
            emptyList()
        }

    /** Deletes the file. */
    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /**
     * Records uncaught exceptions on any thread, then hands them on to the handler that was set
     * before, so the system still sees the crash (and Play vitals still count it). The write is
     * synchronous, because the process dies as soon as the handler returns, and it never throws:
     * a failure to record must not hide the crash itself.
     */
    fun install(context: Context) {
        val file = File(context.applicationContext.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val info = CrashContext(
                    timeMillis = System.currentTimeMillis(),
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    buildType = BuildConfig.BUILD_TYPE,
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    sdkInt = Build.VERSION.SDK_INT,
                    threadName = thread.name,
                )
                val existing = if (file.exists()) file.readText() else ""
                file.writeText(prepend(existing, format(info, error)))
            } catch (_: Throwable) {
                // Out of memory, disk full, a stack too deep to print: nothing more to do here.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
