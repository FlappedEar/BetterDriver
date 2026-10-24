package com.flappedear.betterdriver.timing.gear

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

// Which gear the car is in and which gear pulls hardest at a speed (FBBD-326), from the engine speed
// and the road speed the car itself recorded. The gen-1 FR-S Arek drives has the 6-speed automatic,
// driven on track in M with the paddles (UI-278 A), so the coach can ask for a gear. Engine speed
// over road speed is fixed in each gear (the tyre and the final drive only scale it), so the gears
// show as peaks of that ratio; the known gearbox's step pattern numbers them. How hard each gear
// pulls comes from the same sessions: the car's acceleration at full throttle, per gear and speed.
// Air drag and rolling resistance are the same in every gear at one speed, so two gears compare
// without them. Measured on Arek's sessions: the FA20's torque dips at 3500-4000 rpm and is highest
// from 4500 to 6500, so at Silesia's 58-79 km/h apexes 2nd pulls about 0.26 g and 3rd about 0.17 g.

/** A gearbox's ratios, lowest gear first; only the steps between them matter (the tyre scales the rest). */
data class GearSpec(val name: String, val ratios: List<Double>)

/**
 * The gen-1 FR-S / 86 / BRZ 6-speed automatic (Aisin A960E): 3.538, 2.060, 1.404, 1.000, 0.713,
 * 0.582. 2nd to 4th match the peaks of Arek's own sessions within 1 % (FBBD-326).
 */
val FrsAutomatic = GearSpec("FR-S 6AT", listOf(3.538, 2.060, 1.404, 1.000, 0.713, 0.582))

/** The gearboxes the app knows. */
val KnownGearboxes = listOf(FrsAutomatic)

/** One reading of the car, in time order: [rpm] and [pedalPct] null where not read. */
data class DriveSample(val tSeconds: Double, val speedMps: Double, val rpm: Double?, val pedalPct: Double?)

/** At [speedMps], full throttle in a gear accelerates the car by [accelMps2] (median of [samples] readings). */
@Serializable
data class PullPoint(val speedMps: Double, val accelMps2: Double, val samples: Int)

/** Engine speed per road speed in one gear, and how hard it pulls at the speeds it was driven at full throttle. */
@Serializable
data class GearPull(val gear: Int, val rpmPerMps: Double, val pull: List<PullPoint>)

/** A car's gears as its own sessions show them. */
@Serializable
data class Gearbox(
    val spec: String,
    /** The gears seen, lowest first; numbered as the car numbers them. */
    val gears: List<GearPull>,
    /** The engine speed the car was driven to (99th percentile at full throttle): above it a gear is used up. */
    val maxRpm: Double,
) {
    /** The gear the car is in at [rpm] and [speedMps]; null when they fit no gear (a slipping converter, a shift). */
    @Suppress("ReturnCount")
    fun gearAt(rpm: Double, speedMps: Double): Int? {
        if (speedMps < MinGearSpeedMps || rpm < MinGearRpm) return null
        val r = rpm / speedMps
        val best = gears.minByOrNull { abs(ln(r / it.rpmPerMps)) } ?: return null
        return best.gear.takeIf { abs(ln(r / best.rpmPerMps)) <= GearToleranceLog }
    }

    /** How hard [gear] pulls at [speedMps], from the nearest speed measured within [PullStepMps]; null if unknown. */
    @Suppress("ReturnCount")
    fun pullAt(gear: Int, speedMps: Double): Double? {
        val g = gears.firstOrNull { it.gear == gear } ?: return null
        if (speedMps * g.rpmPerMps > maxRpm) return null
        return g.pull.minByOrNull { abs(it.speedMps - speedMps) }
            ?.takeIf { abs(it.speedMps - speedMps) <= PullStepMps }?.accelMps2
    }

    /**
     * The gear to be in at [speedMps] instead of [current]: a lower one that pulls at least
     * [MinPullGain] harder there and has room to rev ([ExitRoomShare] of [maxRpm] still above it).
     * Null when [current] is already the one, or the sessions do not show both gears at this speed.
     */
    fun lowerGear(current: Int, speedMps: Double): Int? {
        val now = pullAt(current, speedMps) ?: return null
        return gears.filter { it.gear < current && speedMps * it.rpmPerMps <= maxRpm * ExitRoomShare }
            .mapNotNull { g -> pullAt(g.gear, speedMps)?.let { g.gear to it } }
            .filter { it.second >= now * (1 + MinPullGain) && it.second > 0 }
            .maxByOrNull { it.second }?.first
    }
}

/** Below this speed a converter or a clutch slips too much to read a gear. */
const val MinGearSpeedMps = 5.0

/** Below this the engine idles or the converter is open. */
const val MinGearRpm = 1200.0

/** Engine speed over road speed within 4 % of a gear's is that gear; the FR-S's steps are 30 % or more. */
const val GearToleranceLog = 0.04

/** Full throttle: the pedal at least this far down. */
const val FullPedalPct = 90.0

/** Pull is binned every this many metres per second (7.2 km/h). */
const val PullStepMps = 2.0

/** A pull bin needs this many readings. */
const val MinPullSamples = 10

/** A gear seen in fewer readings than this share of the in-gear ones is a converter slipping past it, not a gear. */
const val MinGearShare = 0.03

/** Speed change is read over this many seconds either side, as the lap analysis reads the pedal. */
const val AccelHalfWindowSeconds = 0.3

/** A lower gear is worth asking for when it pulls this much harder (15 %). */
const val MinPullGain = 0.15

/** A gear has room to the exit while its engine speed at the apex is below this share of the top. */
const val ExitRoomShare = 0.8

/**
 * The gearbox [samples] (one session or several, each in time order) show: the gears of the best
 * fitting [specs] gearbox, with how hard each pulls. Null without engine speed, or when no known
 * gearbox fits (a hybrid's or a CVT's engine speed follows no fixed ratios).
 */
@Suppress("ReturnCount")
fun fitGearbox(samples: List<List<DriveSample>>, specs: List<GearSpec> = KnownGearboxes): Gearbox? {
    val ratios = samples.flatten()
        .filter { it.rpm != null && it.speedMps >= MinGearSpeedMps && it.rpm >= MinGearRpm }
        .map { it.rpm!! / it.speedMps }
    if (ratios.size < MinFitSamples) return null
    val logs = ratios.map { ln(it) }
    var best: Fit? = null
    for (spec in specs) {
        val (scale, share) = bestScale(logs, spec) ?: continue
        if (best == null || share > best.share) best = Fit(spec, scale, share)
    }
    val fit = best?.takeIf { it.share >= MinFitShare } ?: return null
    val perGear = fit.spec.ratios.map { fit.scale * it }
    val counts = IntArray(perGear.size)
    for (l in logs) nearest(l, perGear)?.let { counts[it]++ }
    val seen = perGear.indices.filter { counts[it] >= counts.sum() * MinGearShare }
    val full = samples.flatMap { pullReadings(it, perGear) }
    val revs = full.map { it.rpm }.ifEmpty { samples.flatten().mapNotNull { it.rpm } }.sorted()
    val maxRpm = revs[((revs.size - 1) * MaxRpmQuantile).roundToInt()]
    val gears = seen.map { i ->
        val pull = full.filter { it.gear == i }.groupBy { (it.speedMps / PullStepMps).roundToInt() }
            .filter { it.value.size >= MinPullSamples }
            .map { (bin, rs) -> PullPoint(bin * PullStepMps, median(rs.map { it.accelMps2 }), rs.size) }
            .sortedBy { it.speedMps }
        GearPull(i + 1, perGear[i], pull)
    }
    return Gearbox(fit.spec.name, gears, maxRpm)
}

private class Fit(val spec: GearSpec, val scale: Double, val share: Double)

private class PullReading(val gear: Int, val speedMps: Double, val rpm: Double, val accelMps2: Double)

/** Fewer readings with engine speed than this are too few to find gears in. */
private const val MinFitSamples = 200

/** The fitted gearbox must explain at least this share of the readings. */
private const val MinFitShare = 0.6

private const val MaxRpmQuantile = 0.99

/** The scale (engine speed per road speed per unit of ratio) that puts the most readings on [spec]'s gears. */
private fun bestScale(logs: List<Double>, spec: GearSpec): Pair<Double, Double>? {
    // Every reading is a candidate for every gear: the scale it implies, scored by how many readings
    // then sit on some gear. A coarse histogram of the candidates keeps it to a few hundred tries.
    val candidates = logs.flatMap { l -> spec.ratios.map { l - ln(it) } }
        .groupingBy { (it / CandidateStep).roundToInt() }.eachCount()
        .entries.sortedByDescending { it.value }.take(MaxCandidates).map { it.key * CandidateStep }
    return candidates.map { c ->
        val gears = spec.ratios.map { Math.exp(c) * it }
        // Refine: the median scale of the readings that sit on a gear.
        val on = logs.mapNotNull { l -> nearest(l, gears)?.let { l - ln(spec.ratios[it]) } }
        val scale = if (on.isEmpty()) Math.exp(c) else Math.exp(median(on))
        val refined = spec.ratios.map { scale * it }
        scale to logs.count { nearest(it, refined) != null }.toDouble() / logs.size
    }.maxByOrNull { it.second }
}

private const val CandidateStep = 0.01
private const val MaxCandidates = 30

/** Index of the gear in [perGear] that log ratio [l] sits on, within [GearToleranceLog]; null when none. */
private fun nearest(l: Double, perGear: List<Double>): Int? {
    val i = perGear.indices.minByOrNull { abs(l - ln(perGear[it])) } ?: return null
    return i.takeIf { abs(l - ln(perGear[i])) <= GearToleranceLog }
}

/** Full-throttle readings of one run with their gear and acceleration from the speed either side. */
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // Each reading that does not count is skipped.
private fun pullReadings(run: List<DriveSample>, perGear: List<Double>): List<PullReading> {
    val out = mutableListOf<PullReading>()
    var lo = 0
    var hi = 0
    for (i in run.indices) {
        val s = run[i]
        val rpm = s.rpm
        if (rpm == null || s.pedalPct == null || s.pedalPct < FullPedalPct) continue
        if (s.speedMps < MinGearSpeedMps || rpm < MinGearRpm) continue
        val gear = nearest(ln(rpm / s.speedMps), perGear) ?: continue
        while (lo < i && run[lo].tSeconds < s.tSeconds - AccelHalfWindowSeconds) lo++
        if (hi < i) hi = i
        while (hi + 1 < run.size && run[hi + 1].tSeconds <= s.tSeconds + AccelHalfWindowSeconds) hi++
        val span = run[hi].tSeconds - run[lo].tSeconds
        if (span < AccelHalfWindowSeconds) continue
        out += PullReading(gear, s.speedMps, rpm, (run[hi].speedMps - run[lo].speedMps) / span)
    }
    return out
}

private fun median(values: List<Double>): Double {
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
}
