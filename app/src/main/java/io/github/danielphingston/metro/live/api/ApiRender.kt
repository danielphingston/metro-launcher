package io.github.danielphingston.metro.live.api

import io.github.danielphingston.metro.data.ApiSlot
import io.github.danielphingston.metro.data.ApiView
import java.util.concurrent.TimeUnit

/** One repeating row of an API tile's list. */
data class ApiRow(val primary: String, val secondary: String, val image: String?)

/**
 * A view's templates worked out against the downloaded JSON: the text of each slot, the list's
 * rows, when the text next changes by itself ([changesAt], for countdowns), and the first problem
 * a template had (shown in the editor; the tile just leaves that part out).
 */
data class ApiRendered(
    val slots: Map<ApiSlot, String> = emptyMap(),
    val rows: List<ApiRow> = emptyList(),
    val changesAt: Long? = null,
    val error: String? = null,
) {
    fun text(slot: ApiSlot): String = slots[slot].orEmpty()

    /** An https address from the picture slot, else null: pictures come over https only. */
    val image: String? get() = slots[ApiSlot.IMAGE]?.trim()?.takeIf { it.startsWith("https://") }
    val link: String? get() = slots[ApiSlot.LINK]?.trim()?.takeIf { it.startsWith("https://") }
}

object ApiRender {
    /**
     * [view]'s templates against [root] at [context]'s time; a slot left empty in [view] uses
     * [fallback]'s (the expanded view falls back to the tile). At most [maxRows] rows.
     */
    fun render(
        view: ApiView,
        root: Any?,
        context: FormulaContext,
        maxRows: Int,
        fallback: ApiView? = null,
        formulas: ApiFormulas = BuiltInFormulas,
    ): ApiRendered {
        var changesAt: Long? = null
        var error: String? = null
        fun take(e: Evaluated): String {
            e.changesAt?.let { at -> changesAt = changesAt?.let { minOf(it, at) } ?: at }
            if (error == null) error = e.error
            return e.text
        }
        val slots = ApiSlot.entries.mapNotNull { slot ->
            val template = view.slot(slot).ifEmpty { fallback?.slot(slot).orEmpty() }
            if (template.isEmpty()) null else slot to take(formulas.evaluate(template, root, context)).trim()
        }.toMap()
        val list = view.list ?: fallback?.list
        val rows = if (list == null || maxRows <= 0) {
            emptyList()
        } else {
            ApiJson.items(root, list.path, maxRows).map { item ->
                ApiRow(
                    take(formulas.evaluate(list.primary, root, context, item)).trim(),
                    if (list.secondary.isEmpty()) "" else take(formulas.evaluate(list.secondary, root, context, item)).trim(),
                    if (list.image.isEmpty()) null else take(formulas.evaluate(list.image, root, context, item)).trim().takeIf { it.startsWith("https://") },
                )
            }
        }
        return ApiRendered(slots, rows, changesAt, error)
    }
}

/**
 * When API tiles fetch. Nothing here runs by itself: a tile calls these while Start is on screen
 * and the screen is on, and stops when either goes. Pure, so it is tested without a clock.
 */
object ApiRefresh {
    /** A tile due within this of another's wake refreshes with it, so tiles share radio wake-ups. */
    val TOLERANCE_MS = TimeUnit.SECONDS.toMillis(30)

    /** After a failed download, the longest wait before the next try. */
    val RETRY_MS = TimeUnit.MINUTES.toMillis(5)

    /** The live poll never goes faster than this in battery saver. */
    private val SAVER_LIVE_MIN_MS = TimeUnit.SECONDS.toMillis(30)

    /** The tile's own refresh interval; battery saver doubles it. */
    fun tileInterval(minutes: Int, batterySaver: Boolean): Long =
        TimeUnit.MINUTES.toMillis(minutes.toLong()) * if (batterySaver) 2 else 1

    /** The expanded view's live interval; battery saver triples it, to at least 30 seconds. */
    fun liveInterval(seconds: Int, batterySaver: Boolean): Long {
        val ms = TimeUnit.SECONDS.toMillis(seconds.toLong())
        return if (batterySaver) maxOf(ms * 3, SAVER_LIVE_MIN_MS) else ms
    }

    /**
     * When the tile should next refresh: [intervalMs] after its last good answer, or after a
     * failure min(interval, 5 min) after it; rounded up to a whole minute of the clock, so tiles
     * that fall due together wake the radio once. Never before [now] + 1 s.
     */
    fun nextWake(cached: CachedApi?, intervalMs: Long, now: Long): Long {
        val due = when {
            cached == null || (!cached.hasData && !cached.failing) -> now
            cached.failing -> cached.failedAt + minOf(intervalMs, RETRY_MS)
            else -> cached.fetchedAt + intervalMs - TOLERANCE_MS
        }
        val minute = TimeUnit.MINUTES.toMillis(1)
        val aligned = (due + minute - 1) / minute * minute
        return maxOf(aligned, now + 1000)
    }

    /** How long the live poll waits after [failures] failures in a row: doubling, to at most 5 minutes. */
    fun liveDelay(intervalMs: Long, failures: Int): Long =
        if (failures <= 0) intervalMs else minOf(intervalMs shl minOf(failures, 6), RETRY_MS)

    /** Whether the expanded view may poll live now. */
    fun livePolling(liveSeconds: Int, wifiOnly: Boolean, metered: Boolean): Boolean = liveSeconds > 0 && !(wifiOnly && metered)
}
