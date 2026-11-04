package com.flappedear.betterdriver.timing.live

/**
 * The lap number people see for the lap timer's lap [number] (FBBD-450). On a circuit the timer's
 * lap 1 is the out lap from session start to the first start/finish crossing; people count full
 * laps from the line, as the session analysis does, so the first full lap is lap 1 and the out lap
 * from session start is 0 (shown as "Out lap", never as a number: see [lapMark]). A point-to-point
 * track's runs already count from 1. The timer's own numbers stay as they are inside the app (lap
 * keys, video parts, coach references): only what is shown changes.
 */
fun shownLapNumber(number: Int, pointToPoint: Boolean = false): Int = if (pointToPoint) number else number - 1

/**
 * The lap's mark on the video overlay's lap tile (FBBD-450): "OUT" on an out lap, "IN" on an in lap,
 * else its [shownLapNumber]. [kind] is null for the lap being driven when the session stopped.
 */
fun lapMark(number: Int, kind: LapKind?, pointToPoint: Boolean = false): String = when {
    kind == LapKind.Out || (!pointToPoint && number <= 1) -> "OUT"
    kind == LapKind.In -> "IN"
    else -> shownLapNumber(number, pointToPoint).toString()
}

/** "LAP 7", "OUT LAP", "IN LAP", "RUN 3": the lap over the overlay's track map (FBBD-450). */
fun lapMapCaption(number: Int, kind: LapKind?, pointToPoint: Boolean = false): String {
    val mark = lapMark(number, kind, pointToPoint)
    return when (mark) {
        "OUT", "IN" -> "$mark LAP"
        else -> "${if (pointToPoint) "RUN" else "LAP"} $mark"
    }
}

/**
 * A lap's name in a list ("Lap 7", "Run 2"), or "Out lap" for the out lap from session start, which
 * has no number people count (FBBD-450).
 */
fun lapName(number: Int, pointToPoint: Boolean = false): String = when {
    pointToPoint -> "Run $number"
    number <= 1 -> "Out lap"
    else -> "Lap ${shownLapNumber(number)}"
}

/** The lap's part of a saved video's file name (FBBD-450): "lap7", "run2", "outlap". */
fun lapFileWord(number: Int, pointToPoint: Boolean = false): String = when {
    pointToPoint -> "run$number"
    number <= 1 -> "outlap"
    else -> "lap${shownLapNumber(number)}"
}
