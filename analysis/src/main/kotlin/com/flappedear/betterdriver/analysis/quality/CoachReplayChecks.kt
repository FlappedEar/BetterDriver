package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent

// What the coach said lap by lap, checked from a recording's events: the checks the drive-log reports
// made by hand on Arek's Demo track replays (FBBD-244, FBBD-242, FBBD-243, FBBD-245), so the emulator
// replay of his sessions and the JVM replay make them on every pull request. They read only the
// events the recorder logs (coach_cue, lap_line), the same in the continuous log and in a session.
// Not part of the report the app shows.

private const val MillisPerSecond = 1000.0

/** Wrong cues quoted in a check. */
private const val MaxShown = 3

/** The coach's event of a spoken cue (the coach module's CoachEvents.CUE). */
private const val CueKind = "coach_cue"

/** The coach's event when it goes quiet for the rest of a lap (coach module's CoachEvents.QUIET). */
private const val QuietKind = "coach_quiet"

/** A lap at most this much slower than the session's median lap is at race pace. */
private const val AtPaceShare = 1.05

/** Words of the English phrases (coach Phrases) that no Polish cue may contain (FBBD-245). */
private val EnglishWords =
    Regex("""\b(turn|brake|later|earlier|apex|worked|best|lap|more|less)\b""", RegexOption.IGNORE_CASE)

/** One cue as logged: the lap it was said on, its kind, its advice field, its language tag and its words. */
data class ReplayCue(
    val tMicros: Long,
    val lap: Int,
    val kind: String,
    val advice: String?,
    val language: String,
    val text: String,
    val praisedLap: Int?,
    /** For a corner cue, the lap of the pass its advice was measured from. */
    val beforeLap: Int?,
)

/** The coach went quiet on [lap] for [reason]: "cooling_down" or "pit_lane" (FBBD-202). */
data class ReplayQuiet(val tMicros: Long, val lap: Int, val reason: String)

/** One lap as live timing logged it at the line. */
data class ReplayLap(val number: Int, val kind: String, val timeMillis: Long)

/** The cues in [events], in order. */
fun replayCues(events: List<RecorderEvent>): List<ReplayCue> = events.ofKind(CueKind).mapNotNull { e ->
    val text = e.text.substringAfter(" text=", "")
    val f = fields(e.text.substringBefore(" text="))
    val lap = f["lap"]?.toIntOrNull() ?: return@mapNotNull null
    ReplayCue(
        e.tMicros, lap, f["kind"].orEmpty(), f["advice"], f["lang"] ?: "en", text,
        f["praised"]?.substringBefore(',')?.toIntOrNull(),
        f["before"]?.substringBefore(',')?.toIntOrNull(),
    )
}

/** When the coach went quiet in [events], in order. */
fun replayQuiet(events: List<RecorderEvent>): List<ReplayQuiet> = events.ofKind(QuietKind).mapNotNull { e ->
    val f = fields(e.text)
    ReplayQuiet(e.tMicros, f["lap"]?.toIntOrNull() ?: return@mapNotNull null, f["reason"].orEmpty())
}

/** The laps live timing finished in [events], in order. */
fun replayLaps(events: List<RecorderEvent>): List<ReplayLap> = events.ofKind(EventKinds.LAP_LINE).mapNotNull { e ->
    val f = fields(e.text)
    ReplayLap(f["lap"]?.toIntOrNull() ?: return@mapNotNull null, f["kind"].orEmpty(), f["time_ms"]?.toLongOrNull() ?: 0)
}

/**
 * The coach's checks of one recording, as the drive-log reports made them by hand:
 *
 * - FBBD-244: once coaching starts, no lap at race pace goes by without a cue unless the coach logged
 *   why it was quiet; a lap out of the pit lane is never taken as cooling down; no advice is measured
 *   from a lap driven cooling down. A lap at race pace gets a cue even if a lift for traffic quieted
 *   the coach for a while (FBBD-252); laps at race pace it went quiet on are listed.
 * - FBBD-242: corner cues said with numbers give an absolute target.
 * - FBBD-243: praise is said, never for a lap driven cooling down.
 * - FBBD-245: every cue is in [language] ("pl" or "en").
 *
 * A check the recording cannot show (too few coached laps, no cue said with numbers, no praise) is not
 * measured, so a short run does not fail; [minCoachedLaps] is how many coached laps it needs.
 */
fun coachReplayChecks(events: List<RecorderEvent>, language: String, minCoachedLaps: Int = 2): List<QualityMetric> {
    val cues = replayCues(events)
    val quiet = replayQuiet(events)
    val laps = replayLaps(events).filter { it.kind == "Timed" }
    val median = laps.map { it.timeMillis }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val atPace = laps.filter { median != null && it.timeMillis <= median * AtPaceShare }.map { it.number }.toSet()
    // A lap driven cooling down: the coach took it as cooling down, and it was slower than race pace. A lap
    // it went quiet on late, after the corners it was driven at pace through, does not count.
    val cooling = quiet.filter { it.reason == "cooling_down" && it.lap !in atPace }.map { it.lap }.toSet()
    // Coaching starts at the first corner cue; the laps from there on that finished are coached laps.
    val first = cues.firstOrNull { it.kind == "Corner" }?.lap
    val coached = laps.filter { first != null && it.number >= first }
    return listOf(
        silentLaps(cues, quiet, coached, atPace, minCoachedLaps),
        outOfPitLane(quiet),
        adviceFromCoolDown(cues, cooling),
        absoluteTargets(cues),
        praise(cues, cooling),
        language(cues, language),
    )
}

@Suppress("LongParameterList") // Each is something the check is made from.
private fun silentLaps(
    cues: List<ReplayCue>,
    quiet: List<ReplayQuiet>,
    coached: List<ReplayLap>,
    atPace: Set<Int>,
    min: Int,
): QualityMetric {
    val id = "coach.silent_laps"
    val label = "Coached laps without a cue"
    val criterion = "0 laps without a cue once coaching starts, unless slow and the coach logged why " +
        "(FBBD-244, FBBD-252)"
    if (coached.size < min) {
        return notMeasured(id, label, criterion, "${coached.size} coached lap(s), $min needed")
    }
    val quietLaps = quiet.map { it.lap }.toSet()
    val said = coached.associate { lap -> lap.number to cues.count { it.lap == lap.number } }
    // A lap at race pace is coached even when the coach went quiet on it for a while (FBBD-252).
    val silent = coached.filter { said[it.number] == 0 && (it.number !in quietLaps || it.number in atPace) }
        .map { it.number }
    val quietAtPace = coached.filter { it.number in quietLaps && it.number in atPace }
        .joinToString { lap -> "${lap.number} (${"%.1f".format(lap.timeMillis / MillisPerSecond)} s, " +
            quiet.filter { it.lap == lap.number }.joinToString("/") { it.reason } + ")" }
    return judged(
        id, label, silent.size.toDouble(), "laps", silent.isEmpty(), Basis.MEASURED, criterion,
        "cues per coached lap (" + said.entries.joinToString { "${it.key}: ${it.value}" } + ")" +
            (if (silent.isEmpty()) "" else "; silent: $silent") +
            (if (quietAtPace.isEmpty()) "" else "; quiet at race pace: $quietAtPace"),
    )
}

/** FBBD-244: getting up to speed out of the pit lane is not cooling down. */
private fun outOfPitLane(quiet: List<ReplayQuiet>): QualityMetric {
    val id = "coach.pit_out"
    val label = "Laps out of the pit lane taken as cool-down"
    val criterion = "0 laps out of the pit lane taken as cooling down (FBBD-244)"
    val pitOut = quiet.filter { it.reason == "pit_lane" }.map { it.lap + 1 }.toSet()
    if (pitOut.isEmpty()) return notMeasured(id, label, criterion, "no lap ended in the pit lane")
    val wrong = quiet.filter { it.reason == "cooling_down" && it.lap in pitOut }.map { it.lap }.distinct()
    return judged(
        id, label, wrong.size.toDouble(), "laps", wrong.isEmpty(), Basis.MEASURED, criterion,
        "laps out of the pit lane: ${pitOut.sorted()}" + (if (wrong.isEmpty()) "" else "; taken as cool-down: $wrong"),
    )
}

/** FBBD-244: no corner cue's advice is measured from a pass driven cooling down. */
private fun adviceFromCoolDown(cues: List<ReplayCue>, cooling: Set<Int>): QualityMetric {
    val id = "coach.advice_from_cool_down"
    val label = "Advice measured from a cool-down lap"
    val criterion = "0 corner cues measured from a lap driven cooling down (FBBD-244)"
    val corner = cues.filter { it.kind == "Corner" && it.beforeLap != null }
    if (corner.isEmpty()) return notMeasured(id, label, criterion, "no corner cue")
    val wrong = corner.filter { it.beforeLap in cooling }
    return judged(
        id, label, wrong.size.toDouble(), "cues", wrong.isEmpty(), Basis.MEASURED, criterion,
        "${corner.size} corner cue(s); cool-down laps: ${cooling.sorted()}" +
            (if (wrong.isEmpty()) "" else "; from them: ") +
            wrong.joinToString { "\"${it.text}\" (lap ${it.beforeLap})" },
    )
}

private fun absoluteTargets(cues: List<ReplayCue>): QualityMetric {
    val id = "coach.absolute"
    val label = "Corner cues with an absolute target"
    val criterion = "at least 1 corner cue says where to brake or the apex speed (FBBD-242)"
    val corner = cues.filter { it.kind == "Corner" }
    val precise = corner.filter { it.advice != null && ":vague" !in it.advice }
    if (precise.isEmpty()) {
        return notMeasured(id, label, criterion, "${corner.size} corner cue(s), none said with its numbers")
    }
    val absolute = precise.filter { "at=" in it.advice.orEmpty() || "goal=" in it.advice.orEmpty() }
    return judged(
        id, label, absolute.size.toDouble(), "cues", absolute.isNotEmpty(), Basis.MEASURED, criterion,
        "${absolute.size} of ${precise.size} corner cues said with numbers" +
            absolute.firstOrNull()?.let { ", e.g. \"${it.text}\"" }.orEmpty(),
    )
}

private fun praise(cues: List<ReplayCue>, cooling: Set<Int>): QualityMetric {
    val id = "coach.praise"
    val label = "Praise"
    val criterion = "at least 1 praise, none for a corner driven cooling down (FBBD-243)"
    val praise = cues.filter { it.kind == "Praise" }
    if (praise.isEmpty()) return notMeasured(id, label, criterion, "no corner was praised")
    val wrong = praise.filter { it.praisedLap in cooling }
    return judged(
        id, label, praise.size.toDouble(), "cues", wrong.isEmpty(), Basis.MEASURED, criterion,
        "${praise.size} praise(s), e.g. \"${praise.first().text}\"" +
            (if (wrong.isEmpty()) "" else "; for cool-down laps: ${wrong.map { it.praisedLap }}"),
    )
}

private fun language(cues: List<ReplayCue>, language: String): QualityMetric {
    val id = "coach.language"
    val label = "Cues in the coach's language"
    val criterion = "every cue in the language set ($language) (FBBD-245)"
    if (cues.isEmpty()) return notMeasured(id, label, criterion, "no cue")
    val wrong = cues.filter { it.language != language || (language != "en" && EnglishWords.containsMatchIn(it.text)) }
    return judged(
        id, label, wrong.size.toDouble(), "cues", wrong.isEmpty(), Basis.MEASURED, criterion,
        "${cues.size} cue(s)" + (if (wrong.isEmpty()) "" else ", wrong: ") +
            wrong.take(MaxShown).joinToString { "\"${it.text}\"" },
    )
}
