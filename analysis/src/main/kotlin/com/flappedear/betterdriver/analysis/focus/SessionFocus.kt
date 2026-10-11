package com.flappedear.betterdriver.analysis.focus

import com.flappedear.betterdriver.analysis.SessionAnalysis

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

/** Up to three areas to look at before the next session, or none. */
fun sessionFocusAreas(analysis: SessionAnalysis, lapLabel: (Any?) -> String = { "lap $it" }): List<FocusArea> =
    sessionFocusInputs(analysis, lapLabel)?.let(::selectFocusAreas).orEmpty()
