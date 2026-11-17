package io.github.danielphingston.metro.ui.tiles

import io.github.danielphingston.metro.live.tracking.ParcelEntry
import io.github.danielphingston.metro.live.tracking.ParcelLog
import io.github.danielphingston.metro.live.tracking.ParcelStage
import io.github.danielphingston.metro.live.tracking.TrackItem
import io.github.danielphingston.metro.live.tracking.TrackKind
import io.github.danielphingston.metro.live.tracking.TrackProgress
import io.github.danielphingston.metro.live.tracking.TrackingParser

/**
 * One line of the tracking tile, already cleared for showing: with notification content off (or a
 * SECRET notification) [status] is a generic phrase, [detail] and [plate] are empty and [showPicture]
 * is false. [otp] is null with content off; otherwise it is there but stays hidden until tapped.
 */
data class TrackRow(
    val key: String,
    val appKey: String,
    val packageName: String,
    val kind: TrackKind,
    val status: String,
    val detail: String,
    val etaAt: Long?,
    val progress: TrackProgress?,
    val otp: String?,
    val plate: String?,
    val stage: ParcelStage?,
    val shortText: String?,
    val showPicture: Boolean,
) {
    /** A trip or order happening now, as opposed to a parcel kept for today. */
    val isLive: Boolean get() = stage == null
}

/** What the tracking tile shows; pure, so its rules are unit tested. */
data class TrackingModel(val state: State, val rows: List<TrackRow>, val now: Long) {
    enum class State { LOCKED, NO_ACCESS, EMPTY, ACTIVE }

    val top: TrackRow? get() = rows.firstOrNull()
    val live: List<TrackRow> get() = rows.filter { it.isLive }
    val parcels: List<TrackRow> get() = rows.filter { !it.isLive }

    /** Whether the tile must be redrawn as minutes pass: only while some line counts down. */
    val ticks: Boolean get() = rows.any { it.etaAt != null }

    /** For TalkBack: what the tile says at a glance. */
    fun description(appName: (TrackRow) -> String): String = when (state) {
        State.LOCKED -> "tracking, unlock metro plus to use"
        State.NO_ACCESS -> "tracking, turn on notification access"
        State.EMPTY -> "tracking, nothing on the way"
        State.ACTIVE -> buildString {
            append("tracking")
            for (row in rows.take(3)) {
                append(", ").append(appName(row)).append(" ").append(row.status)
                minutesLeft(row.etaAt, now)?.let { append(", ").append(spokenEta(it)) }
            }
        }
    }

    companion object {
        val EMPTY = TrackingModel(State.EMPTY, emptyList(), 0L)

        /** A Metro Plus tile after a refund: it shows nothing it caught. */
        val LOCKED = TrackingModel(State.LOCKED, emptyList(), 0L)

        /**
         * The tile for the [live] trips from the listener and the [parcels] kept for today, at [now].
         * Live trips with an ETA come first (soonest first), then other live trips (newest first),
         * then parcels (out for delivery before arriving today).
         */
        fun of(
            live: List<TrackItem>,
            parcels: List<ParcelEntry>,
            accessGranted: Boolean,
            showContent: Boolean,
            now: Long,
        ): TrackingModel {
            if (!accessGranted) return TrackingModel(State.NO_ACCESS, emptyList(), now)
            val liveRows = live.asSequence()
                .filter { it.kind != TrackKind.PARCEL }
                .map { row(it, showContent, now) }
                .sortedWith(compareBy<Pair<TrackRow, Long>> { it.first.etaAt == null }.thenBy { it.first.etaAt ?: 0L }.thenByDescending { it.second })
                .map { it.first }
                .toList()
            val parcelRows = ParcelLog.live(parcels, now)
                .sortedWith(compareByDescending<ParcelEntry> { it.stage.ordinal }.thenByDescending { it.caughtAt })
                .map { row(it, showContent) }
            val rows = liveRows + parcelRows
            return TrackingModel(if (rows.isEmpty()) State.EMPTY else State.ACTIVE, rows, now)
        }

        private fun row(item: TrackItem, showContent: Boolean, now: Long): Pair<TrackRow, Long> {
            val words = showContent && !item.secret
            val eta = item.etaAt?.takeIf { it > now - TrackingParser.STALE_MS }
            return TrackRow(
                key = item.key,
                appKey = item.appKey,
                packageName = item.packageName,
                kind = item.kind,
                status = item.status.takeIf { words && it.isNotBlank() } ?: generic(item.kind),
                detail = if (words) item.detail else "",
                etaAt = eta,
                progress = item.progress,
                otp = item.otp.takeIf { showContent },
                plate = item.plate.takeIf { words },
                stage = null,
                shortText = item.shortText.takeIf { words },
                showPicture = words,
            ) to item.postTime
        }

        private fun row(entry: ParcelEntry, showContent: Boolean): TrackRow {
            val words = showContent && entry.title.isNotBlank()
            return TrackRow(
                key = entry.key,
                appKey = entry.appKey,
                packageName = entry.packageName,
                kind = TrackKind.PARCEL,
                status = if (words) entry.title else stageText(entry.stage),
                detail = if (showContent) entry.detail else "",
                etaAt = null,
                progress = null,
                otp = null,
                plate = null,
                stage = entry.stage,
                shortText = null,
                showPicture = showContent,
            )
        }

        fun generic(kind: TrackKind): String = when (kind) {
            TrackKind.RIDE -> "ride on the way"
            TrackKind.FOOD -> "order on the way"
            TrackKind.PARCEL -> "arriving today"
            TrackKind.OTHER -> "in progress"
        }

        fun stageText(stage: ParcelStage): String = when (stage) {
            ParcelStage.OUT_FOR_DELIVERY -> "out for delivery"
            else -> "arriving today"
        }

        /** Whole minutes left until [etaAt], rounded up; 0 once it is due. Null without an ETA. */
        fun minutesLeft(etaAt: Long?, now: Long): Int? {
            etaAt ?: return null
            val ms = etaAt - now
            if (ms <= 0) return 0
            return ((ms + 59_999) / 60_000).toInt()
        }

        /** The small tile's ETA: "now", "8m", "1h", "2h". */
        fun shortEta(minutes: Int): String = when {
            minutes <= 0 -> "now"
            minutes < 60 -> "${minutes}m"
            else -> "${minutes / 60}h"
        }

        /** The big ETA as number and unit: ("8", "min"), ("1:05", "h"), ("now", ""). */
        fun bigEta(minutes: Int): Pair<String, String> = when {
            minutes <= 0 -> "now" to ""
            minutes < 60 -> minutes.toString() to "min"
            else -> "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}" to "h"
        }

        private fun spokenEta(minutes: Int): String = when {
            minutes <= 0 -> "due now"
            minutes == 1 -> "1 minute"
            minutes < 60 -> "$minutes minutes"
            else -> "${minutes / 60} hours ${minutes % 60} minutes"
        }
    }
}
