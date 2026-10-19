package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionStore
import java.io.IOException
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

    /**
     * For example "12 cues · 5 of 8 corners better"; "No cues" when the coach said nothing. In the
     * language the cues were said in (FBBD-245).
     */
    val summary: String
        get() {
            val words = ReviewWords.of(cues.firstOrNull()?.cue?.language ?: CoachLanguage.English)
            return when {
                cues.isEmpty() -> words.noCues
                judged == 0 -> words.cues(cues.size)
                else -> "${words.cues(cues.size)} · ${words.better(improved, judged)}"
            }
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
 * praise (FBBD-243) says how much faster the corner was than the pass it beat. In the language the
 * cue was said in (FBBD-245).
 */
fun ReviewedCue.outcome(): String? {
    val words = ReviewWords.of(cue.language)
    return if (cue.kind == CueKind.Praise) cue.praiseOutcome(words) else cornerOutcome(words)
}

private fun ReviewedCue.cornerOutcome(words: ReviewWords): String? {
    val r = result
    val before = cue.before
    if (r == null || before == null) return null
    val precise = cue.confidence.precise && r.confidence.precise
    val target = cue.target
    val advice = cue.advice
    val anchor = advice?.brakeAnchor(before)
    val change = when {
        precise && advice is Advice.CarrySpeed && advice.goalKmh != null ->
            words.apex(r.after.minKmh.roundToInt()) + moved(r.after.minKmh - before.minKmh, NearKmh, false, words)
        precise && anchor != null ->
            words.brakedAt((anchor - r.after.brakeAt).roundToInt()) +
                moved(r.after.brakeAt - before.brakeAt, NearMeters, true, words)
        advice is Advice.CarrySpeed ->
            speedChange(r.after.minKmh - before.minKmh, target?.let { it.minKmh - r.after.minKmh }, precise, words)
        else ->
            brakeChange(r.after.brakeAt - before.brakeAt, target?.let { it.brakeAt - r.after.brakeAt }, precise, words)
    }
    return "$change · ${timeChange(r.gainMicros, precise, words)}"
}

/**
 * How far a corner cue could be trusted (FBBD-177), for the review: "High confidence", or for one
 * said without its number why, as "Medium confidence: positions far apart". Null for a lap-time cue.
 * In the language the cue was said in (FBBD-245).
 */
fun Cue.confidenceText(): String? {
    if (kind != CueKind.Corner) return null
    val words = ReviewWords.of(language)
    val label = words.confidence(confidence)
    // A doubt worked out when the cue was made (a gap's length) is in English; read it back by its code.
    val reasons = doubts.joinToString("; ") { if (language == CoachLanguage.English) it.text else words.doubt(it.code) }
    return if (doubts.isEmpty()) label else "$label: $reasons"
}

/** "8 m later than before", "3 more than before", or the same as before when under [near]; [m] for metres. */
private fun moved(by: Double, near: Int, m: Boolean, words: ReviewWords): String {
    val n = by.roundToInt()
    return if (abs(n) < near) words.same(m) else words.moved(n, m)
}

private const val NearMeters = 2
private const val NearKmh = 1
private const val MicrosPerSecond = 1_000_000.0

/** Null when [n] is under [near] either way ("the same"), else whether it is above. */
private fun direction(n: Int, near: Int): Boolean? = if (abs(n) < near) null else n > 0

/**
 * "Braked 9 m later, 4 m short of best", or without [precise] "Braked later, short of best"; [toBest]
 * is how much later the best pass braked.
 */
private fun brakeChange(moved: Double, toBest: Double?, precise: Boolean, words: ReviewWords): String {
    val m = moved.roundToInt()
    val first = words.braked(direction(m, NearMeters), abs(m).takeIf { precise })
    val gap = toBest?.roundToInt() ?: return first
    return "$first, " + words.toBestPoint(direction(gap, NearMeters), abs(gap).takeIf { precise })
}

/** "Minimum speed 3 km/h higher, 2 km/h under best", or without [precise] "Minimum speed higher, under best". */
private fun speedChange(moved: Double, toBest: Double?, precise: Boolean, words: ReviewWords): String {
    val v = moved.roundToInt()
    val first = words.minSpeed(direction(v, NearKmh), abs(v).takeIf { precise })
    val gap = toBest?.roundToInt() ?: return first
    return "$first, " + words.toBestSpeed(direction(gap, NearKmh), abs(gap).takeIf { precise })
}

/** "0.12 s gained" or "0.05 s lost" across the corner; without [precise] "time gained" or "time lost". */
private fun timeChange(gainMicros: Long, precise: Boolean, words: ReviewWords): String =
    words.time(gainMicros >= 0, (abs(gainMicros) / MicrosPerSecond).takeIf { precise })

/** "0.21 s faster than before the cue" or "0.30 s faster than your best until then" (FBBD-243). */
private fun Cue.praiseOutcome(words: ReviewWords): String? {
    val beaten = before
    val pass = praised
    val kind = praise
    return if (beaten == null || pass == null || kind == null) {
        null
    } else {
        words.faster((beaten.timeMicros - pass.timeMicros) / MicrosPerSecond, kind == Praise.Worked)
    }
}
