package com.flappedear.betterdriver.coach

/**
 * What the coach says (FBBD-97): short English phrases, numbers allowed (Arek, FBBD-6) when the
 * evidence is strong enough for them (FBBD-177). Corners are named by the track's corner numbers
 * (FBBD-172), the ones the lap analysis shows.
 *
 * A corner cue says the target first, then the change from the last pass (FBBD-242, Arek's picks
 * UI-87 C and UI-88 C): "Turn 3, brake at 100, 10 later", "Turn 3, 60 at the apex, 5 more". Advice
 * without a target (logged before FBBD-242) is said as before: "Turn 3, brake 10 metres later".
 */
object Phrases {
    fun corner(corner: Corner, advice: Advice): String = "Turn ${corner.label}, " + advice(advice)

    /** The advice part of a corner cue: "brake at 100, 10 later". */
    private fun advice(a: Advice): String = when (a) {
        is Advice.BrakeLater -> when {
            !a.precise -> "brake a little later"
            a.at != null -> "brake at ${a.at}, ${a.meters} later"
            else -> "brake ${a.meters} metres later"
        }
        is Advice.BrakeEarlier -> when {
            a.precise && a.at != null && a.meters != null -> "brake at ${a.at}, ${a.meters} earlier"
            else -> "brake earlier"
        }
        is Advice.CarrySpeed -> when {
            !a.precise -> "carry a little more speed"
            a.goalKmh != null -> "${a.goalKmh} at the apex, ${a.kmh} more"
            else -> "carry ${a.kmh} more"
        }
    }

    /**
     * [cue] with its target only, for a straight too short for the whole cue (FBBD-242): "Turn 3,
     * brake at 100", "Turn 3, 60 at the apex". Null when the cue has no target or is already short.
     */
    fun shortCorner(cue: Cue): String? {
        val a = cue.advice
        val target = when {
            a == null || !a.precise -> null
            a is Advice.BrakeLater && a.at != null -> "brake at ${a.at}"
            a is Advice.BrakeEarlier && a.at != null -> "brake at ${a.at}"
            a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} at the apex"
            else -> null
        }
        val turn = cue.heardTurn
        return if (target == null || turn == null) null else "Turn $turn, $target".takeIf { it != cue.text }
    }

    /**
     * The coach line on the track screen for advice with its target (UI-87 C, UI-88 C):
     * "Brake at 100 m · 10 later", "60 km/h at the apex · 5 more". Null for any other advice, whose
     * line is the spoken words.
     */
    fun line(a: Advice): String? = when {
        !a.precise -> null
        a is Advice.BrakeLater && a.at != null -> "Brake at ${a.at} m · ${a.meters} later"
        a is Advice.BrakeEarlier && a.at != null && a.meters != null -> "Brake at ${a.at} m · ${a.meters} earlier"
        a is Advice.CarrySpeed && a.goalKmh != null -> "${a.goalKmh} km/h at the apex · ${a.kmh} more"
        else -> null
    }

    /** "1 32 point 4", read by text-to-speech as "one thirty-two point four"; "Best lap, ..." for a new best. */
    fun lapTime(millis: Long, best: Boolean): String {
        val tenths = (millis + HalfTenth) / MillisPerTenth
        val minutes = tenths / TenthsPerMinute
        val seconds = tenths % TenthsPerMinute / TenthsPerSecond
        val tenth = tenths % TenthsPerSecond
        val time = if (minutes > 0) {
            "$minutes ${"%02d".format(seconds)} point $tenth"
        } else {
            "$seconds point $tenth"
        }
        return if (best) "Best lap, $time" else time
    }

    /**
     * About how long text-to-speech takes to say [text] at normal rate: a fixed start plus a time per
     * word, counting a two-digit number as two words and a number over 99 as the parts it is said in
     * ("1 oh 5", [CueClips.numberKeys]). An estimate; the audio delay setting covers lag.
     */
    fun durationMicros(text: String): Long {
        val words = text.split(' ', ',').filter { it.isNotBlank() }.sumOf { w ->
            if (w.all(Char::isDigit)) CueClips.numberKeys(w).sumOf(::numberWords) else 1L
        }
        return StartMicros + words * WordMicros
    }

    private fun numberWords(key: String): Long =
        if (key.all(Char::isDigit) && key.length >= 2 && key.toInt() > SpokenAsOneWord) 2L else 1L

    private const val HalfTenth = 50L
    private const val MillisPerTenth = 100L
    private const val TenthsPerSecond = 10L
    private const val TenthsPerMinute = 600L
    private const val SpokenAsOneWord = 20
    private const val StartMicros = 200_000L
    private const val WordMicros = 400_000L
}
