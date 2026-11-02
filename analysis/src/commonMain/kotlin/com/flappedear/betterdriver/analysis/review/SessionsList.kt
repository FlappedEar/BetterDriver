package com.flappedear.betterdriver.analysis.review

import com.flappedear.betterdriver.format.fixed

/**
 * The Sessions list's words and grouping, the same on Android and iOS (FBBD-360): Compose and SwiftUI
 * only draw them. The platform gives each session's local day (days since 1970 in the device's time
 * zone) and formats a date in the device's language; everything else is decided here.
 */
data class SessionListEntry(val id: String, val localDay: Long, val track: String)

/**
 * One day of the Sessions list (UI-308 A): its heading ("Today · Silesia Ring" when the day had one
 * track), its sessions in the order given, the day's [tracks], and whether each card names its track
 * ([trackOnRows], only when the day had more than one).
 */
data class SessionListDay(
    val localDay: Long,
    val title: String,
    val ids: List<String>,
    val tracks: List<String>,
    val trackOnRows: Boolean,
)

/**
 * [entries] by the day they started on, in the order given within a day and the days in the order of
 * their first session (the list comes newest first). [dateText] formats a local day that is neither
 * [today] nor the day before, as "Sun 19 Oct".
 */
fun sessionListDays(entries: List<SessionListEntry>, today: Long, dateText: (Long) -> String): List<SessionListDay> =
    entries.groupBy { it.localDay }.map { (day, list) ->
        val tracks = list.map { it.track }.filter { it.isNotBlank() }.distinct()
        val label = sessionDayLabel(day, today, dateText)
        SessionListDay(
            localDay = day,
            title = tracks.singleOrNull()?.let { "$label · $it" } ?: label,
            ids = list.map { it.id },
            tracks = tracks,
            trackOnRows = tracks.size > 1,
        )
    }

/** "Today", "Yesterday", else [dateText] of [day]. */
fun sessionDayLabel(day: Long, today: Long, dateText: (Long) -> String): String = when (day) {
    today -> "Today"
    today - 1 -> "Yesterday"
    else -> dateText(day)
}

/** "16:23 · Silesia Ring": when the session started ([time], as the device shows it), and where when [withTrack]. */
fun sessionRowTitle(time: String, track: String, withTrack: Boolean): String =
    listOf(time, track.takeIf { withTrack }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")

/**
 * The delete question (UI-48 A): the session's [title], what goes and how much space it frees, on
 * this [device] ("phone", "iPhone", "iPad").
 */
fun sessionDeleteText(title: String, bytes: Long?, video: Boolean, device: String): String {
    val what = if (video) "its data and video" else "its data"
    val size = bytes?.let { " (${sessionSizeText(it)})" }.orEmpty()
    return "$title: $what$size will be removed from this $device. " +
        "Files still on a GoPro stay there. This can't be undone."
}

/** "820 KB", "350 MB", "2.4 GB". */
fun sessionSizeText(bytes: Long): String = when {
    bytes >= GB -> "${fixed(bytes / GB.toDouble(), 1)} GB"
    bytes >= MB -> "${bytes / MB} MB"
    else -> "${maxOf(1, bytes / KB)} KB"
}

private const val KB = 1_000L
private const val MB = 1_000_000L
private const val GB = 1_000_000_000L
