package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.ConsistentLaps
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.confidence.segmentSeconds
import com.flappedear.betterdriver.analysis.confidence.usableLaps
import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.recorder.Confidence
import kotlin.math.abs
import kotlin.math.max

// The passes through a next-session focus corner (FBBD-20): the faster ones against the others, and
// how they differ, each difference graded by the passes it rests on (FBBD-177).

/** The usable laps through a corner, by lap index: the fastest third (two at least) and the others. */
internal class PassGroups(val faster: List<Int>, val other: List<Int>)

internal fun passGroups(analysis: SessionAnalysis, segmentId: String): PassGroups? {
    val timed = analysis.usableLaps(segmentId)
        .mapNotNull { i -> analysis.segmentSeconds(segmentId, i)?.takeIf { it.isFinite() }?.let { i to it } }
        .sortedBy { it.second }
    val k = max(MinimumSupportingLaps, timed.size / FastShareDivisor)
    // Three laps at least, so there is one other pass besides the faster ones.
    return if (timed.size < ConsistentLaps || timed.size <= k) {
        null
    } else {
        PassGroups(timed.take(k).map { it.first }, timed.drop(k).map { it.first })
    }
}

private const val FastShareDivisor = 3

/** One pass through a corner: its lap index, what was observed there, and how far that lap can be trusted there. */
private class Pass(val lap: Int, val observation: CornerLapObservation, val level: Confidence)

/**
 * How the faster passes through [segmentId] differ from the others, where the difference is worth a
 * change. Each change may carry its number ([FocusChange.precise]) only when every input to it is
 * (FBBD-174, FBBD-177): its channel measured (not speeds calculated from positions, not a braking
 * point inferred from the speed or read from a brake channel of unknown or calculated origin), its
 * unit known, and every pass it uses graded High there.
 */
internal fun cornerChanges(analysis: SessionAnalysis, segmentId: String, groups: PassGroups): List<FocusChange> {
    val metrics = analysis.cornerMetrics[segmentId] ?: return emptyList()
    fun passes(laps: List<Int>) = laps.mapNotNull { i ->
        metrics.getOrNull(i)?.observation?.let { Pass(i, it, analysis.confidence.lap(segmentId, i).level) }
    }
    val faster = passes(groups.faster)
    val other = passes(groups.other)
    val speedMeasured = analysis.provenance.speed?.measured != false
    return listOfNotNull(
        brakingChange(faster, other) { method -> analysis.provenance.braking(method)?.measured != false },
        speedChange(FocusMetric.MinimumSpeed, faster, other, speedMeasured) { it.minimumSpeed },
        speedChange(FocusMetric.ExitSpeed, faster, other, speedMeasured) { it.exitSpeed },
    )
}

/** The values of [passes] that [value] gives, finite, with the lowest confidence of the passes they come from. */
private fun values(passes: List<Pass>, value: (CornerLapObservation) -> Double?): Pair<List<Double>, Confidence> {
    val used = passes.mapNotNull { p -> value(p.observation)?.takeIf { it.isFinite() }?.let { it to p.level } }
    return used.map { it.first } to Confidence.lowest(used.map { it.second })
}

/** Measured braking points when both groups have them, else inferred ones; never the two mixed. */
private fun brakingChange(
    faster: List<Pass>,
    other: List<Pass>,
    measuredBy: (method: String) -> Boolean,
): FocusChange? {
    for (provenance in listOf("measured", "inferred")) {
        val point = { o: CornerLapObservation -> o.brakingPointMeters.takeIf { o.brakingProvenance == provenance } }
        val (f, fLevel) = values(faster, point)
        val (o, oLevel) = values(other, point)
        // A median of one pass is no median: each group needs two at least.
        if (f.size < MinimumSupportingLaps || o.size < MinimumSupportingLaps) continue
        val change = FocusChange(
            FocusMetric.BrakingPoint, median(o), median(f), "m", measuredBy(provenance), f.size, o.size,
            minOf(fLevel, oLevel),
        )
        return change.takeIf { abs(it.delta) >= FocusChangeBrakingMeters }
    }
    return null
}

private fun speedChange(
    metric: FocusMetric,
    faster: List<Pass>,
    other: List<Pass>,
    measured: Boolean,
    value: (CornerLapObservation) -> Double?,
): FocusChange? {
    val all = (faster + other).map { it.observation }
    val unit = all.firstOrNull()?.speedUnit.orEmpty()
    val (f, fLevel) = values(faster, value)
    val (o, oLevel) = values(other, value)
    // Speeds in different units are not one difference; each group needs two passes at least.
    val comparable = all.all { sameSpeedUnit(it.speedUnit, unit) } &&
        f.size >= MinimumSupportingLaps && o.size >= MinimumSupportingLaps
    val change = if (comparable) {
        FocusChange(metric, median(o), median(f), unit, measured, f.size, o.size, minOf(fLevel, oLevel))
    } else {
        null
    }
    return change?.takeIf { abs(it.delta) >= FocusChangeSpeedFraction * abs(it.other) && it.delta != 0.0 }
}

internal fun median(values: List<Double>): Double {
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}
