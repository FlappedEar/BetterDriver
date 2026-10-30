package com.flappedear.betterdriver.analysis.progression

import com.flappedear.betterdriver.analysis.coaching.CornerPassRecord
import com.flappedear.betterdriver.analysis.coaching.CornerRecord
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceInferred
import com.flappedear.betterdriver.analysis.corners.BrakingProvenanceMeasured
import com.flappedear.betterdriver.analysis.data.normalizedSpeedUnit
import com.flappedear.betterdriver.analysis.data.sameSpeedUnit
import com.flappedear.betterdriver.analysis.focus.median
import com.flappedear.betterdriver.recorder.Confidence

// One corner's metrics across a progression (FBBD-181), from the passes its records keep: Low
// passes never counted, braking points and pickups compared measured with measured or inferred with
// inferred, never the two mixed, speeds in one unit; precise only from High passes of measured
// channels in a known unit.

/** Corner [c] (the latest session's) at each point, from [groups]' records of it. */
internal fun cornerProgression(c: CornerRecord, groups: List<List<CornerRecord>>): CornerProgression {
    val all = groups.flatten()
    val braking = pedalWay(all, CornerPassRecord::brakingMeters, CornerPassRecord::brakingProvenance)
    val pickup = pedalWay(all, CornerPassRecord::pickupMeters, CornerPassRecord::pickupProvenance)
    val unit = all.map { it.speedUnit.trim() }.filter { it.isNotEmpty() }
        .groupingBy { normalizedSpeedUnit(it).ifEmpty { it.lowercase() } }.eachCount()
        .maxByOrNull { it.value }?.let { u -> all.first { sameSpeedUnit(it.speedUnit, u.key) }.speedUnit.trim() }
        .orEmpty()
    fun series(metric: ProgressionMetric, unitOf: String, of: (List<CornerRecord>) -> MetricValue?) =
        MetricSeries(metric, groups.map(of), unitOf)
    return CornerProgression(
        c.cornerId,
        c.name,
        listOf(
            series(ProgressionMetric.CornerTime, "s") { g -> times(g) { if (it.isEmpty()) null else median(it) } },
            series(ProgressionMetric.BrakingPoint, "m") { g -> braking?.let { braking(g, it) } },
            series(ProgressionMetric.MinimumSpeed, unit) { g -> speed(g, unit, CornerPassRecord::minimumSpeed) },
            series(ProgressionMetric.ExitSpeed, unit) { g -> speed(g, unit, CornerPassRecord::exitSpeed) },
            series(ProgressionMetric.ThrottlePickup, "m") { g -> pickup?.let { pickup(g, it) } },
            series(ProgressionMetric.CornerSpread, "s") { g -> times(g, ::spread) },
        ),
    )
}

/** The counted passes of [corners]: never one graded Low. */
private fun counted(corners: List<CornerRecord>): List<CornerPassRecord> =
    corners.flatMap { c -> c.passes.filter { it.level != Confidence.LOW } }

private fun allHigh(passes: List<CornerPassRecord>) = passes.all { it.level == Confidence.HIGH }

/** [of] the counted corner times of [g], from [MinimumSupportingLaps] passes at least. */
private fun times(g: List<CornerRecord>, of: (List<Double>) -> Double?): MetricValue? {
    val passes = counted(g)
    if (passes.size < MinimumSupportingLaps) return null
    return of(passes.map { it.seconds })?.let { MetricValue(it, passes.size, allHigh(passes), "s") }
}

/**
 * How a pedal point (braking or pickup) is compared over [all] of a corner's records: measured
 * when any has [MinimumSupportingLaps] measured points, else inferred; null when neither has.
 * The progression of a corner never mixes the two.
 */
private fun pedalWay(
    all: List<CornerRecord>,
    value: (CornerPassRecord) -> Double?,
    way: (CornerPassRecord) -> String,
): String? = listOf(BrakingProvenanceMeasured, BrakingProvenanceInferred).firstOrNull { w ->
    all.any { c -> counted(listOf(c)).count { value(it) != null && way(it) == w } >= MinimumSupportingLaps }
}

private fun braking(g: List<CornerRecord>, way: String) =
    pedal(g, way, CornerPassRecord::brakingMeters, CornerPassRecord::brakingProvenance) { it.brakeMeasured }

private fun pickup(g: List<CornerRecord>, way: String) =
    pedal(g, way, CornerPassRecord::pickupMeters, CornerPassRecord::pickupProvenance) { it.throttleMeasured }

/** The median pedal point found [w]ay over [g]'s counted passes; precise only when measured by a measured pedal. */
private fun pedal(
    g: List<CornerRecord>,
    w: String,
    value: (CornerPassRecord) -> Double?,
    way: (CornerPassRecord) -> String,
    pedalMeasured: (CornerRecord) -> Boolean,
): MetricValue? {
    val passes = counted(g).filter { value(it) != null && way(it) == w }
    if (passes.size < MinimumSupportingLaps) return null
    val measured = w == BrakingProvenanceMeasured && g.all(pedalMeasured)
    return MetricValue(median(passes.mapNotNull(value)), passes.size, measured && allHigh(passes), "m", w)
}

/** The median speed of [g]'s counted passes in [unit]; corners in another unit are left out. */
private fun speed(g: List<CornerRecord>, unit: String, value: (CornerPassRecord) -> Double?): MetricValue? {
    val same = g.filter { unit.isNotEmpty() && sameSpeedUnit(it.speedUnit, unit) }
    val passes = counted(same).filter { value(it) != null }
    if (passes.size < MinimumSupportingLaps) return null
    val precise = normalizedSpeedUnit(unit).isNotEmpty() && same.all { it.speedMeasured } && allHigh(passes)
    return MetricValue(median(passes.mapNotNull(value)), passes.size, precise, unit)
}
