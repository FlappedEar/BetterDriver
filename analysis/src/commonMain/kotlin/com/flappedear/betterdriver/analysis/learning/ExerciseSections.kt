package com.flappedear.betterdriver.analysis.learning

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType

/**
 * One corner's section as the learning coach times an exercise there (FBBD-419): the corner and,
 * when the segment after it is a straight, that exit straight ([exitId]); [downstreamId] is the
 * segment after the section, which must not get slower. [seconds] is the section's time on each
 * counted lap, by lap number; [downstreamSeconds] the downstream segment's.
 */
data class CornerSection(
    val segmentId: String,
    val name: String,
    val exitId: String?,
    val downstreamId: String?,
    val seconds: Map<Int, Double>,
    val downstreamSeconds: Map<Int, Double>,
)

/**
 * What one session gives the learning coach (FBBD-417, FBBD-419): the brake-to-throttle [gaps] at
 * every corner, and every corner's [sections] timed lap by lap.
 */
data class SessionLearning(
    val gaps: BrakeToThrottleGaps,
    val sections: List<CornerSection> = emptyList(),
) {
    fun section(segmentId: String): CornerSection? = sections.firstOrNull { it.segmentId == segmentId }
}

/** [analysis]'s gaps and sections. */
fun sessionLearning(analysis: SessionAnalysis): SessionLearning =
    SessionLearning(brakeToThrottleGaps(analysis), cornerSections(analysis))

/**
 * Every corner of [analysis] with its exit straight, timed on each counted lap (a lap the analysis
 * may use as a reference) whose sectors cover the whole section. Empty without approved segments.
 */
fun cornerSections(analysis: SessionAnalysis): List<CornerSection> {
    val segments = analysis.segments?.segments.orEmpty()
    if (segments.size < 2) return emptyList()
    return segments.withIndex().filter { it.value.type == TrackSegmentType.Corner }.map { (i, corner) ->
        val next = segments[(i + 1) % segments.size]
        val exit = next.takeIf { it.type == TrackSegmentType.Straight && it.id != corner.id }
        val downstream = segments[(i + if (exit != null) 2 else 1) % segments.size].takeIf { it.id != corner.id }
        val seconds = HashMap<Int, Double>()
        val downstreamSeconds = HashMap<Int, Double>()
        analysis.laps.timedLaps.forEachIndexed { k, lap ->
            if (!lap.referenceEligible) return@forEachIndexed
            val times = analysis.lapTimes.getOrNull(k) ?: return@forEachIndexed
            val c = times.sector(corner.id)?.seconds
            val e = if (exit != null) times.sector(exit.id)?.seconds else 0.0
            if (c != null && e != null) seconds[lap.number] = c + e
            downstream?.let { d -> times.sector(d.id)?.seconds?.let { downstreamSeconds[lap.number] = it } }
        }
        CornerSection(corner.id, corner.name, exit?.id, downstream?.id, seconds, downstreamSeconds)
    }
}
