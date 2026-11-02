package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.analysis.review.SessionFacts
import com.flappedear.betterdriver.format.fixed
import kotlin.math.roundToLong

// Drive's Pinned cards (UI-308 A), the same on Android and iOS (FBBD-421): what to work on next time
// at the track the phone is at, and how the last day there went. Each comes from data the apps
// already keep; a card without data is left out. The apps only draw them.

/** What Drive pins: [focus] for the next session here, and the [lastTime] here; null when there is none. */
data class DrivePinned(val focus: DriveFocus? = null, val lastTime: LastTimeHere? = null)

/**
 * The next session's focus at this track (FBBD-20), as the last session here kept it: its corners,
 * the primary first, and the time a lap they offer together, when the analysis gave one.
 */
data class DriveFocus(val corners: List<String>, val secondsToFind: Double?)

/**
 * The last day at this track: its [epochDay] (days since 1970-01-01 in the phone's time zone), its
 * best lap, and each session's best lap, oldest first.
 */
data class LastTimeHere(val epochDay: Long, val bestMillis: Long, val sessionBests: List<Long>)

/**
 * A listed session as Drive's cards read it: its [track] name, when it started, the [epochDay] it
 * started on in the phone's time zone, and whether it still records.
 */
data class PinnedSession(
    val id: String,
    val track: String,
    val startUtcMillis: Long,
    val epochDay: Long,
    val recording: Boolean,
)

/** [record]'s corners for Drive, the primary first; null without any. */
fun driveFocus(record: SessionFocusRecord?): DriveFocus? {
    val targets = record?.targets?.sortedByDescending { it.primary }.orEmpty()
    if (targets.isEmpty()) return null
    val times = targets.mapNotNull { it.timeAvailableSeconds }
    val total = times.takeIf { it.isNotEmpty() }?.sum()?.let { (it * Hundredths).roundToLong() / Hundredths }
    return DriveFocus(targets.map { it.name }, total)
}

private const val Hundredths = 100.0

/**
 * The last day at [track] among [sessions] (newest first, as listed): the finished sessions there
 * on the newest day that has one with a counted lap ([facts]). Null when [track] is unknown or none has.
 */
fun lastTimeHere(sessions: List<PinnedSession>, facts: Map<String, SessionFacts>, track: String?): LastTimeHere? {
    val timed = sessions.filter {
        !track.isNullOrBlank() && !it.recording && it.track == track && facts[it.id]?.bestMillis != null
    }
    return timed.maxOfOrNull { it.epochDay }?.let { day ->
        val bests = timed.filter { it.epochDay == day }
            .sortedBy { it.startUtcMillis }
            .mapNotNull { facts[it.id]?.bestMillis }
        LastTimeHere(day, bests.min(), bests)
    }
}

/** "1.53 s a lap to find": the focus's time, to the hundredth. */
fun focusTimeText(seconds: Double): String = "${fixed(seconds, 2)} s a lap to find"

/** "Corner 3 · Corner 1 · Corner 7": the card's value. */
fun focusCornersText(focus: DriveFocus): String = focus.corners.joinToString(" · ")

/** "1.53 s a lap to find · Corner 3 first": the line under the corners. */
fun focusDetailText(focus: DriveFocus): String =
    listOfNotNull(focus.secondsToFind?.let(::focusTimeText), "${focus.corners.first()} first").joinToString(" · ")

/** "best · 2 sessions": the line under the last day's best lap. */
fun lastTimeDetailText(last: LastTimeHere): String {
    val n = last.sessionBests.size
    return "best · $n session${if (n == 1) "" else "s"}"
}
