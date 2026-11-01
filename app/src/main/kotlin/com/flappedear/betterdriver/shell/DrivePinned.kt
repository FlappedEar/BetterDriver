package com.flappedear.betterdriver.shell

import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong

// Drive's Pinned cards (UI-308 A): what to work on next time at the track the phone is at, and how
// the last day there went. Each comes from data the app already keeps; a card without data is left out.

/** What Drive pins: [focus] for the next session here, and the [lastTime] here; null when there is none. */
data class DrivePinned(val focus: DriveFocus? = null, val lastTime: LastTimeHere? = null)

/**
 * The next session's focus at this track (FBBD-20), as the last session here kept it: its corners,
 * the primary first, and the time a lap they offer together, when the analysis gave one.
 */
data class DriveFocus(val corners: List<String>, val secondsToFind: Double?)

/** The last day at this track: its [day], its best lap, and each session's best lap, oldest first. */
data class LastTimeHere(val day: LocalDate, val bestMillis: Long, val sessionBests: List<Long>)

/** [record]'s corners for Drive, the primary first; null without any. */
internal fun driveFocus(record: SessionFocusRecord?): DriveFocus? {
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
internal fun lastTimeHere(
    sessions: List<SessionSummary>,
    facts: Map<String, SessionFacts>,
    track: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): LastTimeHere? {
    if (track.isNullOrBlank()) return null
    val timed = sessions.filter {
        it.state != SessionState.RECORDING && it.info.track == track && facts[it.id]?.bestMillis != null
    }
    val (day, list) = byDay(timed, zone).firstOrNull() ?: return null
    val bests = list.sortedBy { it.info.startUtcMillis }.mapNotNull { facts[it.id]?.bestMillis }
    return LastTimeHere(day, bests.min(), bests)
}

/** "1.53 s a lap to find": the focus's time, to the hundredth. */
internal fun focusTimeText(seconds: Double): String = "%.2f s a lap to find".format(Locale.ROOT, seconds)
