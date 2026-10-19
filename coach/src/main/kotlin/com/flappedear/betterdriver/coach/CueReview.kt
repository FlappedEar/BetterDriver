package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionStore
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One spoken cue and, for a corner cue, the pass right after it; null until the corner was driven again. */
data class ReviewedCue(val cue: Cue, val result: CoachEvents.Result?)

/**
 * What the coach said in one session and what followed (FBBD-28, FBBD-99), read back from the
 * session's coach events. [problems] counts cues that could not be said (too late, or no voice).
 */
data class CueReview(val cues: List<ReviewedCue>, val problems: Int) {
    /** Corner cues with a pass after them. */
    val judged: Int get() = cues.count { it.result != null }
    val improved: Int get() = cues.count { it.result?.improved == true }

    /** For example "12 cues · 5 of 8 corners better"; "No cues" when the coach said nothing. */
    val summary: String
        get() = when {
            cues.isEmpty() -> "No cues"
            judged == 0 -> plural(cues.size, "cue")
            else -> "${plural(cues.size, "cue")} · $improved of $judged corners better"
        }

    companion object {
        /**
         * The review of a session's [events]; events other than the coach's are ignored. A restarted
         * coach process numbers its cues from 1 again, so a result belongs to the latest cue with its
         * number before it, not to any cue with that number. A cue that was not said (too late or no
         * voice) ends its number too, so its result is dropped; coach errors are not counted as cues.
         */
        fun of(events: List<RecorderEvent>): CueReview {
            val cues = mutableListOf<ReviewedCue>()
            val latest = HashMap<Int, Int>()
            var unsaid = 0
            for (e in events.sortedBy { it.tMicros }) {
                when (e.kind) {
                    CoachEvents.CUE -> CoachEvents.parseCue(e.tMicros, e.text)?.let {
                        latest[it.seq] = cues.size
                        cues += ReviewedCue(it, null)
                    }
                    CoachEvents.RESULT -> CoachEvents.parseResult(e.text)?.let { r ->
                        val i = latest.remove(r.seq) ?: return@let
                        cues[i] = cues[i].copy(result = r)
                    }
                    CoachEvents.PROBLEM -> unsaidSeq(e.text)?.let {
                        latest.remove(it)
                        unsaid++
                    }
                }
            }
            return CueReview(cues, unsaid)
        }

        /**
         * The review of session [id] in [store]. Only its events are read, so an hour at 25 Hz is not
         * held in memory for them; a missing or damaged session has no cues, never a crash. Run it off
         * the main thread.
         */
        @Suppress("TooGenericExceptionCaught") // As the session list's cue counts (CoachedSessions).
        fun read(store: SessionStore, id: String): CueReview = try {
            of(store.read(store.dir(id), samples = false).events)
        } catch (_: IOException) {
            CueReview(emptyList(), 0)
        } catch (_: RuntimeException) {
            CueReview(emptyList(), 0)
        }

        /** The cue number of a "late seq=…" or "no voice seq=…" problem; null for any other problem. */
        private fun unsaidSeq(text: String): Int? {
            val rest = UnsaidPrefixes.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it) }
            return rest?.substringBefore(' ')?.removePrefix("seq=")?.toIntOrNull()
        }

        private val UnsaidPrefixes = listOf("late ", "no voice ")
    }
}

/**
 * What a corner cue led to, in words for the review: the change it asked for, how far from the best
 * pass that left the driver, and the corner time. Numbers (metres, km/h, seconds) only when the cue
 * and the pass after it are both High confidence (FBBD-177); otherwise the same sentence without
 * them. A cue that said its target (FBBD-242) is reviewed by it: "Braked at 108 m, 8 m later than
 * before" or "Apex 58 km/h, 3 more than before". Null for a lap-time cue or before the next pass. A
 * praise (FBBD-243) says how much faster the corner was than the pass it beat.
 */
fun ReviewedCue.outcome(): String? = if (cue.kind == CueKind.Praise) cue.praiseOutcome() else cornerOutcome()

private fun ReviewedCue.cornerOutcome(): String? {
    val r = result
    val before = cue.before
    if (r == null || before == null) return null
    val precise = cue.confidence.precise && r.confidence.precise
    val target = cue.target
    val advice = cue.advice
    val anchor = advice?.brakeAnchor(before)
    val change = when {
        precise && advice is Advice.CarrySpeed && advice.goalKmh != null ->
            "Apex ${r.after.minKmh.roundToInt()} km/h, " +
                moved(r.after.minKmh - before.minKmh, NearKmh, "more", "less")
        precise && anchor != null ->
            "Braked at ${(anchor - r.after.brakeAt).roundToInt()} m, " +
                moved(r.after.brakeAt - before.brakeAt, NearMeters, "m later", "m earlier", SamePoint)
        advice is Advice.CarrySpeed ->
            speedChange(r.after.minKmh - before.minKmh, target?.let { it.minKmh - r.after.minKmh }, precise)
        else -> brakeChange(r.after.brakeAt - before.brakeAt, target?.let { it.brakeAt - r.after.brakeAt }, precise)
    }
    return "$change · ${timeChange(r.gainMicros, precise)}"
}

/**
 * How far a corner cue could be trusted (FBBD-177), for the review: "High confidence", or for one
 * said without its number why, as "Medium confidence: positions far apart". Null for a lap-time cue.
 */
fun Cue.confidenceText(): String? {
    if (kind != CueKind.Corner) return null
    val label = "${confidence.label} confidence"
    return if (doubts.isEmpty()) label else "$label: " + doubts.joinToString("; ") { it.text }
}

/** "8 m later than before", "3 more than before", or [same] (default "same as before") when under [near]. */
private fun moved(by: Double, near: Int, up: String, down: String, same: String = "same as before"): String {
    val n = by.roundToInt()
    return when {
        abs(n) < near -> same
        n > 0 -> "$n $up than before"
        else -> "${-n} $down than before"
    }
}

private const val SamePoint = "at the same point as before"
private const val NearMeters = 2
private const val NearKmh = 1
private const val MicrosPerSecond = 1_000_000.0

/**
 * "Braked 9 m later, 4 m short of best", or without [precise] "Braked later, short of best"; [toBest]
 * is how much later the best pass braked.
 */
private fun brakeChange(moved: Double, toBest: Double?, precise: Boolean): String {
    val m = moved.roundToInt()
    val first = when {
        abs(m) < NearMeters -> "Braked at the same point"
        m > 0 -> "Braked ${amount(m, "m", precise)}later"
        else -> "Braked ${amount(-m, "m", precise)}earlier"
    }
    val gap = toBest?.roundToInt() ?: return first
    val second = when {
        abs(gap) < NearMeters -> "at the best point"
        gap > 0 -> "${amount(gap, "m", precise)}short of best"
        else -> "${amount(-gap, "m", precise)}past best"
    }
    return "$first, $second"
}

/** "Minimum speed 3 km/h higher, 2 km/h under best", or without [precise] "Minimum speed higher, under best". */
private fun speedChange(moved: Double, toBest: Double?, precise: Boolean): String {
    val v = moved.roundToInt()
    val first = when {
        abs(v) < NearKmh -> "Same minimum speed"
        v > 0 -> "Minimum speed ${amount(v, "km/h", precise)}higher"
        else -> "Minimum speed ${amount(-v, "km/h", precise)}lower"
    }
    val gap = toBest?.roundToInt() ?: return first
    val second = when {
        abs(gap) < NearKmh -> "at best"
        gap > 0 -> "${amount(gap, "km/h", precise)}under best"
        else -> "${amount(-gap, "km/h", precise)}over best"
    }
    return "$first, $second"
}

/** "0.12 s gained" or "0.05 s lost" across the corner; without [precise] "time gained" or "time lost". */
private fun timeChange(gainMicros: Long, precise: Boolean): String {
    val s = if (precise) String.format(Locale.ROOT, "%.2f s", abs(gainMicros) / MicrosPerSecond) else "time"
    return if (gainMicros >= 0) "$s gained" else "$s lost"
}

/** "0.21 s faster than before the cue" or "0.30 s faster than your best until then" (FBBD-243). */
private fun Cue.praiseOutcome(): String? {
    val beaten = before
    val pass = praised
    val against = when (praise) {
        Praise.Worked -> "before the cue"
        Praise.BestYet -> "your best until then"
        null -> null
    }
    return if (beaten == null || pass == null || against == null) {
        null
    } else {
        val seconds = (beaten.timeMicros - pass.timeMicros) / MicrosPerSecond
        String.format(Locale.ROOT, "%.2f s faster than %s", seconds, against)
    }
}

/** "9 m " with [precise], nothing without: the sentence reads the same either way. */
private fun amount(n: Int, unit: String, precise: Boolean) = if (precise) "$n $unit " else ""

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
