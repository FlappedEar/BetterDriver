@file:Suppress("TooManyFunctions") // The view and the words it says.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.analysis.line.PlanAdvice
import com.flappedear.betterdriver.analysis.line.PlanBrakeRoom
import com.flappedear.betterdriver.analysis.line.PlanCorner
import com.flappedear.betterdriver.analysis.line.PlanGear
import com.flappedear.betterdriver.analysis.line.PlanPass
import com.flappedear.betterdriver.analysis.line.PlanPoint
import com.flappedear.betterdriver.analysis.line.perfectLapScene
import com.flappedear.betterdriver.analysis.optimal.PedalState
import com.flappedear.betterdriver.core.ui.format.formatLapTime
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.SteeringPass
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
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
    /** The wheel through the corner, yours against your target (FBBD-321, UI-273 B); null without both. */
    val wheel: WheelTrace? = null,
    /** A braking point beyond your best (FBBD-312); null without one. */
    val beyond: BeyondUi? = null,
    /** A lower gear at the slowest point (FBBD-326); null without one. */
    val gear: GearUi? = null,
)

/**
 * A lower gear at the slowest point (FBBD-326), worded as Arek picked on the UI review page, round
 * 56 (UI-280 A: the gear and the reason in his own laps): [todo] says it, [why] in his own numbers.
 */
data class GearUi(val todo: String, val why: String)

/**
 * A braking point beyond your best (FBBD-312), worded as Arek picked on the UI review page, round
 * 55 (UI-275 C: the whole room, then the step): [todo] says it, [why] in his own numbers, worth
 * about [seconds] for the step.
 */
data class BeyondUi(val seconds: Double, val todo: String, val why: String)

/** One point of a wheel trace: metres driven from the corner's start, degrees of wheel into the turn. */
data class WheelDot(val meters: Double, val deg: Double)

/** The steering wheel through a corner (UI-273 B): your best lap's and your target's. */
data class WheelTrace(val reference: List<WheelDot>, val target: List<WheelDot>) {
    /** The most wheel into the turn on either lap, at least 1, for the chart's scale. */
    val maxIntoDeg: Double get() = maxOf(1.0, (reference + target).maxOfOrNull { it.deg } ?: 0.0)

    /** The most wheel out of the turn (countersteer) on either lap, at least 1/10 of [maxIntoDeg]. */
    val maxOutDeg: Double get() =
        maxOf(maxIntoDeg * MinOutShare, -((reference + target).minOfOrNull { it.deg } ?: 0.0))

    /** The longer of the two, at least 1 m. */
    val meters: Double get() = (reference + target).maxOfOrNull { it.meters }?.coerceAtLeast(1.0) ?: 1.0
}

/** "Where to find time": the session's fastest lap against the target through each corner. */
data class LineUi(
    val bestLapNumber: Int,
    val bestLapSeconds: Double,
    val totalGainSeconds: Double,
    val laps: Int,
    val sessions: Int,
    /** In track order. */
    val corners: List<LineCornerUi>,
    /** The perfect lap to drive in 3D (FBBD-308); null when a corner has no line or road to draw. */
    val scene: PerfectLapScene? = null,
) {
    /**
     * Most time first; corners with nothing to find last, in track order. A braking point beyond
     * your best is in [beyond] instead.
     */
    val ranked: List<LineCornerUi> get() = corners.filter { it.beyond == null }.sortedByDescending { it.gainSeconds }

    /** The braking points beyond your best, most time first (UI-274 A: their own group, under the targets). */
    val beyond: List<LineCornerUi>
        get() = corners.filter { it.beyond != null }.sortedByDescending { it.beyond!!.seconds }
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
                wheel = wheelTrace(c, ::dots),
                beyond = c.brakeRoom?.let(::beyondUi),
                gear = c.gear?.let(::gearUi),
            )
        },
        scene = perfectLapScene(plan),
    )
}

/**
 * [room] in words (UI-275 C): "Brake up to 18 m later · start with 10", and why: "You brake at
 * 0.41 g here, 0.85 g in T4 and T7. Braking that hard all the way is worth about 0.37 s".
 */
fun beyondUi(room: PlanBrakeRoom): BeyondUi {
    val whole = room.fullLaterMeters > room.laterMeters
    val todo = if (whole) {
        "Brake up to ${room.fullLaterMeters} m later · start with ${room.laterMeters}"
    } else {
        "Brake ${room.laterMeters} m later"
    }
    val where = room.trackCorners.map(::cornerTag).joinToString(" and ").ifEmpty { "other corners" }
    val g = { v: Double -> String.format(Locale.ROOT, "%.2f g", v) }
    val why = "You brake at ${g(room.peakG)} here, ${g(room.trackG)} in $where." +
        if (whole) " Braking that hard all the way is worth about ${seconds(room.fullSeconds)} s." else ""
    return BeyondUi(room.seconds, todo, why)
}

/**
 * [gear] in words (UI-280 A): "2nd at the apex", and why: "You were in 3rd on 5 of 12 laps here.
 * In 2nd you are at 92 km/h 100 m later, in 3rd at 84." Without the speeds in both gears, how hard
 * each pulls at full throttle at the apex speed.
 */
fun gearUi(gear: PlanGear): GearUi {
    val lower = ordinal(gear.gear)
    val higher = ordinal(gear.from)
    val laps = "You were in $higher on ${gear.higherLaps} of ${gear.laps} laps here."
    val low = gear.lowerExitKmh
    val high = gear.higherExitKmh
    val reason = if (low != null && high != null) {
        "In $lower you are at ${low.roundToInt()} km/h 100 m later, in $higher at ${high.roundToInt()}."
    } else {
        val g = { v: Double -> String.format(Locale.ROOT, "%.2f g", v) }
        "At ${gear.apexKmh.roundToInt()} km/h on full throttle $lower pulls ${g(gear.lowerPullG)}, " +
            "$higher ${g(gear.higherPullG)}."
    }
    return GearUi("$lower at the apex", "$laps $reason")
}

/** "1st", "2nd", "3rd", "4th" and on. */
private fun ordinal(n: Int) = n.toString() + (listOf("st", "nd", "rd").getOrNull(n - 1) ?: "th")

/** What to do differently through [corner], in words (UI-166 A); one line saying so when nothing. */
@Suppress("ReturnCount")
fun cornerTodo(corner: PlanCorner): List<String> {
    if (corner.gainSeconds <= 0.0) return listOf("Your best lap is as quick as your target here.")
    val a = corner.advice
    if (!a.any) return listOf("The same as your best lap within GPS accuracy: no advice.")
    val todo = adviceTodo(a, corner.reference.steering, corner.target.steering)
    val same = brakeSame(corner).takeIf { a.brakeMeters == null }
    return if (same != null) listOf(same) + todo else todo
}

/**
 * [a] in words, one instruction a line (UI-166 A): brake, apex, speed, throttle, as there is advice
 * for, with the wheel (FBBD-321, round 54) where it is driven, why first (UI-270 B). [reference] and
 * [target] give the wheel's numbers; without them its lines say no number.
 */
fun adviceTodo(a: PlanAdvice, reference: SteeringPass? = null, target: SteeringPass? = null): List<String> {
    val lines = mutableListOf<String>()
    a.brakeMeters?.let { lines += "Brake ${metres(it)} ${laterOrEarlier(it)}" }
    if (SteerAdvice.Less in a.steering) lines += slidLine(reference, target)
    apexLine(a)?.let { lines += it }
    if (SteerAdvice.More in a.steering) lines += wideLine(reference, target)
    if (SteerAdvice.Smoother in a.steering) lines += smoothLine(reference, target)
    a.minimumSpeedKmh?.let { lines += speedLine(it) }
    a.steering.filterIsInstance<SteerAdvice.OpenEarlier>().firstOrNull()
        ?.let { lines += "Straighten ${it.meters} m earlier" }
    a.pickupMeters?.let { lines += "Back on the throttle ${metres(it)} ${laterOrEarlier(it)}" }
    if (SteerAdvice.Oversteer in a.steering) lines += "The rear stepped out: gentler on the throttle"
    return lines
}

private fun slidLine(reference: SteeringPass?, target: SteeringPass?): String {
    val more = reference?.pushDeg?.let { r -> target?.pushDeg?.let { (r - it).roundToInt() } }
    return if (more == null) "Front slid: slower in" else "Front slid: $more° more lock than your target, slower in"
}

private fun wideLine(reference: SteeringPass?, target: SteeringPass?): String {
    val less = reference?.let { r -> target?.let { (it.peakDeg - r.peakDeg).roundToInt() } }
    return if (less == null) "Ran wide: turn in more" else "Ran wide: $less° less lock than your target, turn in more"
}

private fun smoothLine(reference: SteeringPass?, target: SteeringPass?): String {
    val extra = reference?.let { r -> target?.let { r.corrections - it.corrections } }
    return if (extra == null) {
        "One smooth turn of the wheel"
    } else {
        "One smooth turn: you added lock $extra more times than your target"
    }
}

/**
 * The wheel through [c] on your best lap and your target (UI-273 B), with [dots] placing the points
 * so each lap is measured by the distance it drove; null unless both laps have the wheel.
 */
private fun wheelTrace(c: PlanCorner, dots: (List<PlanPoint>) -> List<LineDot>): WheelTrace? {
    val side = if (c.turnsLeft) 1.0 else -1.0
    fun trace(points: List<PlanPoint>): List<WheelDot> {
        val placed = dots(points)
        var driven = 0.0
        return points.indices.mapNotNull { i ->
            if (i > 0) {
                driven += hypot(
                    placed[i].eastMeters - placed[i - 1].eastMeters,
                    placed[i].northMeters - placed[i - 1].northMeters,
                )
            }
            points[i].steerDeg?.let { WheelDot(driven, it * side) }
        }
    }
    val reference = trace(c.reference.line)
    val target = trace(c.target.line)
    return if (reference.size < 2 || target.size < 2) null else WheelTrace(reference, target)
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

/** Room below the zero line of a wheel trace without countersteer: a tenth of the chart's wheel. */
private const val MinOutShare = 0.1

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
