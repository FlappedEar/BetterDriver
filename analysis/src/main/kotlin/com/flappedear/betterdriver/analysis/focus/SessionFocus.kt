package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.confidence.bestGapConfidence
import com.flappedear.betterdriver.analysis.confidence.lossConfidence
import com.flappedear.betterdriver.analysis.confidence.spreadConfidence
import com.flappedear.betterdriver.analysis.confidence.usableLaps
import com.flappedear.betterdriver.recorder.Confidence

/**
 * The focus inputs of one analysed session, as Telemetry's dayFocusInputs builds them for a day:
 * the fastest lap's gap to the fastest time through each segment, every counted lap's losses
 * against the fastest, and each corner's observations on the counted laps. Lap references are lap
 * numbers; [lapLabel] names them. Speeds carry the corners' own unit when they all share one.
 * Null without a fastest lap or segments.
 */
fun sessionFocusInputs(analysis: SessionAnalysis, lapLabel: (Any?) -> String): FocusInputs? {
    val fastestIndex = analysis.laps.fastestLapIndex
    val summary = analysis.summary
    if (fastestIndex == null || summary == null) return null
    val reference = analysis.laps.timedLaps[fastestIndex].number
    val referenceLabel = lapLabel(reference)
    val ranking = analysis.losses?.takeIf { it.valid }
    val eligible = analysis.laps.timedLaps.indices.filter { analysis.laps.timedLaps[it].referenceEligible }
    val names = analysis.segments?.segments.orEmpty().associate { it.id to it.name }
    val corners = analysis.cornerMetrics.entries.sortedBy { it.key }.map { (id, laps) ->
        FocusCorner(id, names[id] ?: id, eligible.mapNotNull { laps.getOrNull(it)?.observation })
    }
    val units = corners.flatMap { c -> c.observations.map { it.speedUnit } }.toSet()
    return FocusInputs(
        referenceLap = reference,
        referenceLabel = referenceLabel,
        comparedLapCount = ranking?.comparedLapCount ?: 0,
        gaps = summary.sectors.mapNotNull { sector ->
            val loss = sector.lossSeconds
            val source = sector.sourceLapReference
            if (loss == null || source == null) {
                null
            } else {
                FocusSectorGap(sector.segmentId, sector.name, loss, reference, referenceLabel, source, lapLabel(source))
            }
        },
        losses = ranking?.losses.orEmpty().map {
            FocusLoss(it.window.segmentId, it.window.name, it.lossSeconds, it.lapReference)
        },
        corners = corners,
        speedUnit = units.singleOrNull().orEmpty(),
    )
}

/**
 * Up to three areas to look at before the next session, or none: [sessionFocus]'s, those of Medium
 * confidence in words without numbers.
 */
fun sessionFocusAreas(analysis: SessionAnalysis, lapLabel: (Any?) -> String = { "lap $it" }): List<FocusArea> =
    sessionFocus(analysis, lapLabel).areas.map { it.area }

/** A focus area and its confidence (FBBD-177); at Medium its observation has no numbers. */
data class GradedFocusArea(val area: FocusArea, val confidence: Graded)

/** A corner or straight left out of the focus areas because the evidence there is weak, and why. */
data class WithheldFocus(val segmentId: String, val name: String, val confidence: Graded)

/** The session's focus areas, graded, and the segments left out for their confidence. */
data class SessionFocus(
    val areas: List<GradedFocusArea> = emptyList(),
    val withheld: List<WithheldFocus> = emptyList(),
)

/**
 * The session's focus areas, gated by confidence (FBBD-177): no precise advice from weak evidence.
 *
 * - Laps graded Low in a segment (a GNSS gap, a hairpin crossover, a stop, the pit lane, an
 *   anomalous lap) are left out of its losses and observations: advice never rests on them.
 * - A best-lap gap needs [MinimumSupportingLaps] laps at least half the gap faster: an improvement
 *   seen on one lap only is never a focus area.
 * - An area graded Low is left out, and [SessionFocus.withheld] says why; the next one takes its
 *   place. An area graded Medium says what it found without numbers ([qualitativeObservation]).
 */
fun sessionFocus(
    analysis: SessionAnalysis,
    lapLabel: (Any?) -> String = { "lap $it" },
    maximum: Int = 3,
): SessionFocus {
    val inputs = sessionFocusInputs(analysis, lapLabel) ?: return SessionFocus()
    val gate = FocusGate(analysis)
    val withheld = LinkedHashMap<Pair<String, FocusAreaKind>, WithheldFocus>()
    fun withhold(id: String, kind: FocusAreaKind, name: String, graded: Graded) {
        withheld.putIfAbsent(id to kind, WithheldFocus(id, name, graded))
    }
    for (gap in inputs.gaps.filter { it.gapSeconds >= FocusMinimumSectorGapSeconds }) {
        val graded = gate.gap(gap.segmentId, gap.gapSeconds, gap.sourceLap)
        if (graded.level == Confidence.LOW) withhold(gap.segmentId, FocusAreaKind.SectorGap, gap.name, graded)
    }
    val named = inputs.gaps.map { it.segmentId to it.name } + inputs.losses.map { it.segmentId to it.name } +
        inputs.corners.map { it.segmentId to it.name }
    for ((id, name) in named) {
        val graded = analysis.confidence.segment(id)
        if (graded.level == Confidence.LOW) FocusAreaKind.entries.forEach { withhold(id, it, name, graded) }
    }
    // Each round withholds at least one more area, so it ends.
    while (true) {
        val graded = selectFocusAreas(gate.usableInputs(inputs, withheld.keys), maximum)
            .map { GradedFocusArea(it, gate.grade(it)) }
        val low = graded.filter { it.confidence.level == Confidence.LOW }
        if (low.isEmpty()) {
            val shown = graded.map { it.area.segmentId }.toSet()
            val left = withheld.values.filter { it.segmentId !in shown }.distinctBy { it.segmentId }
            return SessionFocus(graded.map { worded(it, inputs) }, left)
        }
        low.forEach { withhold(it.area.segmentId, it.area.kind, it.area.name, it.confidence) }
    }
}

/** [graded] as shown: below High, its observation without numbers. */
private fun worded(graded: GradedFocusArea, inputs: FocusInputs): GradedFocusArea =
    if (graded.confidence.level == Confidence.HIGH) {
        graded
    } else {
        graded.copy(area = graded.area.copy(observation = qualitativeObservation(graded.area, inputs)))
    }

/** What the focus areas may rest on, and how far each can be trusted. */
private class FocusGate(private val analysis: SessionAnalysis) {
    private val indexByNumber = analysis.laps.timedLaps.withIndex().associate { it.value.number to it.index }
    private val usable = HashMap<String, Set<Int>>()

    private fun index(lap: Any?): Int? = (lap as? Int)?.let(indexByNumber::get)

    private fun usable(segmentId: String): Set<Int> =
        usable.getOrPut(segmentId) { analysis.usableLaps(segmentId).toSet() }

    private fun usable(segmentId: String, lap: Any?): Boolean = index(lap)?.let { it in usable(segmentId) } == true

    /** Of the fastest lap's gap through [segmentId] to [source]'s time: Low when seen on one lap only. */
    fun gap(segmentId: String, seconds: Double, source: Any?): Graded =
        analysis.bestGapConfidence(segmentId, seconds, source, oneLap = Confidence.LOW)

    /** [inputs] without the [withheld] areas (segment and kind), and without laps graded Low where they were seen. */
    fun usableInputs(inputs: FocusInputs, withheld: Set<Pair<String, FocusAreaKind>>): FocusInputs {
        fun kept(id: String, kind: FocusAreaKind) = (id to kind) !in withheld
        return inputs.copy(
            gaps = inputs.gaps.filter { kept(it.segmentId, FocusAreaKind.SectorGap) },
            losses = inputs.losses.filter {
                kept(it.segmentId, FocusAreaKind.RepeatedLoss) && usable(it.segmentId, it.lap)
            },
            corners = inputs.corners.map { c ->
                val braking = kept(c.segmentId, FocusAreaKind.BrakingSpread)
                val speed = kept(c.segmentId, FocusAreaKind.MinimumSpeedSpread)
                c.copy(
                    observations = c.observations.filter { usable(c.segmentId, it.lapReference) }.map {
                        it.copy(
                            brakingPointMeters = it.brakingPointMeters.takeIf { braking },
                            minimumSpeed = it.minimumSpeed.takeIf { speed },
                        )
                    },
                )
            },
        )
    }

    fun grade(area: FocusArea): Graded {
        val id = area.segmentId
        val examples = listOf(area.lap, area.against).mapNotNull(::index).distinct()
            .fold(Graded.High) { g, i -> g + analysis.confidence.lapOnly(id, i) }
        return examples + when (area.kind) {
            FocusAreaKind.SectorGap -> gap(id, area.value, area.against)
            FocusAreaKind.RepeatedLoss -> index(area.lap)?.let { analysis.lossConfidence(id, it, area.value) }
                ?: analysis.spreadConfidence(id)
            FocusAreaKind.BrakingSpread -> analysis.spreadConfidence(id, analysis.provenance.brake)
            FocusAreaKind.MinimumSpeedSpread -> analysis.spreadConfidence(id, analysis.provenance.speed)
        }
    }
}

/**
 * [area]'s observation in words, without the numbers (seconds, metres, speeds) that Medium
 * confidence does not carry (FBBD-177). Lap counts stay: they are not advice.
 */
fun qualitativeObservation(area: FocusArea, inputs: FocusInputs): String = when (area.kind) {
    FocusAreaKind.SectorGap -> {
        val gap = inputs.gaps.firstOrNull { it.segmentId == area.segmentId }
        val best = gap?.bestLapLabel?.let { " ($it)" }.orEmpty()
        val source = gap?.sourceLapLabel ?: "another lap"
        "Your best lap$best was slower through ${area.name} than $source, the fastest recorded there."
    }
    FocusAreaKind.RepeatedLoss ->
        "In ${area.sampleCount} of ${maxOf(inputs.comparedLapCount, area.sampleCount)} compared laps you lost " +
            "time through ${area.name} against ${inputs.referenceLabel}."
    FocusAreaKind.BrakingSpread ->
        "Where braking starts for ${area.name} varies from lap to lap over ${area.sampleCount} laps " +
            "(measured from the brake signal)."
    FocusAreaKind.MinimumSpeedSpread ->
        "The lowest speed through ${area.name} varies from lap to lap over ${area.sampleCount} laps."
}
