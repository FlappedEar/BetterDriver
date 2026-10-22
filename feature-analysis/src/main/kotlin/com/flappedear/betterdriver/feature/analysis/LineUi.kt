package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.PlanPoint
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Pure Kotlin (no Android): the Map tab's "Where to find time" view (FBBD-306, UI-164 A, UI-165 C,
// UI-166 A, UI-167 B), made from the track's line plan and tested on the JVM.

/** One point of a drawn line, in metres east and north of the view's origin. */
data class LineDot(val eastMeters: Double, val northMeters: Double, val pedal: PedalState?)

/** One corner of the view: its tag, time to find, what to do there and where its target comes from. */
data class LineCornerUi(
    val tag: String,
    val gainSeconds: Double,
    /** What to do, one instruction a line (UI-166 A). */
    val todo: List<String>,
    /** Where the target is from, as the card says it. */
    val targetFrom: String,
    val startMeters: Double,
    val endMeters: Double,
    /** His lap through the corner, coloured by pedals, and the target, drawn thin (UI-165 C). */
    val reference: List<LineDot>,
    val target: List<LineDot>,
    /** The road the laps used, left edge then right. */
    val roadLeft: List<LineDot>,
    val roadRight: List<LineDot>,
    /** Where the corner's label sits on the whole-lap map. */
    val anchor: LineDot?,
)

/** "Where to find time": the session's fastest lap against the target through each corner. */
data class LineUi(
    val bestLapNumber: Int,
    val bestLapSeconds: Double,
    val totalGainSeconds: Double,
    val laps: Int,
    val sessions: Int,
    /** In track order. */
    val corners: List<LineCornerUi>,
) {
    /** Most time first; corners with nothing to find last, in track order. */
    val ranked: List<LineCornerUi> get() = corners.sortedByDescending { it.gainSeconds }
}

/**
 * The view of [plan] for session [sessionId] (a target from it says "this session"). Null when
 * the plan is of another session or has no corners.
 */
@Suppress("ReturnCount")
fun lineUi(plan: LinePlan, sessionId: String): LineUi? {
    if (plan.sessionId != sessionId || plan.corners.isEmpty()) return null
    val first = plan.corners.firstNotNullOfOrNull { it.reference.line.firstOrNull() } ?: return null
    val origin = GeoCoordinate(first.latitude, first.longitude)
    fun dots(points: List<PlanPoint>) = points.map {
        val m = projectCoordinate(GeoCoordinate(it.latitude, it.longitude), origin)
        LineDot(m.eastMeters, m.northMeters, it.pedal)
    }
    return LineUi(
        bestLapNumber = plan.referenceLapNumber,
        bestLapSeconds = plan.referenceLapSeconds,
        totalGainSeconds = plan.totalGainSeconds,
        laps = plan.laps,
        sessions = plan.sessions,
        corners = plan.corners.map { c ->
            val reference = dots(c.reference.line)
            LineCornerUi(
                tag = cornerTag(c.name),
                gainSeconds = c.gainSeconds,
                todo = cornerTodo(c),
                targetFrom = targetFrom(c.target, sessionId),
                startMeters = c.startMeters,
                endMeters = c.endMeters,
                reference = reference,
                target = dots(c.target.line),
                roadLeft = dots(c.roadLeft),
                roadRight = dots(c.roadRight),
                anchor = c.reference.apex?.let { dots(listOf(PlanPoint(0.0, it.latitude, it.longitude))).first() }
                    ?: reference.getOrNull(reference.size / 2),
            )
        },
    )
}

/** What to do differently through [corner], in words (UI-166 A); one line saying so when nothing. */
@Suppress("ReturnCount")
fun cornerTodo(corner: PlanCorner): List<String> {
    if (corner.gainSeconds <= 0.0) return listOf("Your best lap is as quick as your target here.")
    val a = corner.advice
    if (!a.any) return listOf("The same as your best lap within GPS accuracy: no advice.")
    val lines = mutableListOf<String>()
    a.brakeMeters?.let { lines += "Brake ${metres(it)} ${laterOrEarlier(it)}" }
        ?: brakeSame(corner)?.let { lines += it }
    apexLine(a)?.let { lines += it }
    a.minimumSpeedKmh?.let { lines += speedLine(it) }
    a.pickupMeters?.let { lines += "Back on the throttle ${metres(it)} ${laterOrEarlier(it)}" }
    return lines
}

private fun brakeSame(corner: PlanCorner) =
    "Brake at the same place".takeIf { corner.reference.brake != null && corner.target.brake != null }

private fun apexLine(a: PlanAdvice): String? {
    val place = a.apexMeters?.let { "${metres(it)} ${laterOrEarlier(it)}" }
    val line = a.tighterMeters?.let { "${metres(it)} ${if (it > 0) "tighter" else "wider"}" }
    return when {
        place != null && line != null -> "Apex $place and $line"
        place != null -> "Apex $place"
        line != null -> "Apex $line"
        else -> null
    }
}

private fun speedLine(kmh: Double): String {
    val n = abs(kmh).roundToInt()
    return if (kmh > 0) "Carry $n km/h more through the slowest point" else "$n km/h slower at the slowest point"
}

private fun metres(value: Double) = "${abs(value).roundToInt()} m"

private fun laterOrEarlier(value: Double) = if (value > 0) "later" else "earlier"

/** "this session, lap 3 (1:51.24)", or the other session's label for "this session". */
private fun targetFrom(pass: PlanPass, sessionId: String): String {
    val where = if (pass.sessionId == sessionId || pass.sessionLabel.isEmpty()) "this session" else pass.sessionLabel
    return "$where, lap ${pass.lapNumber} (${lineLapTime(pass.lapSeconds)})"
}

private const val MillisPerSecond = 1000.0

/** A lap time as the app shows one. */
internal fun lineLapTime(seconds: Double): String = formatLapTime((seconds * MillisPerSecond).roundToLong())

/** The east and north extent of [dots], metres; a unit square without any. */
fun lineExtent(dots: List<LineDot>): Pair<ClosedFloatingPointRange<Double>, ClosedFloatingPointRange<Double>> {
    if (dots.isEmpty()) return 0.0..1.0 to 0.0..1.0
    return dots.minOf { it.eastMeters }..dots.maxOf { it.eastMeters } to
        dots.minOf { it.northMeters }..dots.maxOf { it.northMeters }
}

/** What the view shows: the plan while it is worked out, why there is none, or the view. */
sealed interface LineState {
    data object Reading : LineState

    data class Unavailable(val why: String) : LineState

    data class Ready(val ui: LineUi) : LineState
}
