package com.flappedear.betterdriver.analysis.segments

import com.flappedear.betterdriver.analysis.SessionAnalysis

// The corners of an analysed session the driver can flag for the spoken coach (FBBD-148), shared by
// the Android analysis and the iPad and iPhone review (FBBD-363). The coach module turns each into a
// CornerPick; the analysis never depends on the coach.

/**
 * A corner of a session's analysis as the coach can be told to work on it: its segment [id] and
 * [name], its line on the session's fastest lap ([lats], [lons], every few metres in driving order),
 * and the revision of the track's corner identity (FBBD-172) when the analysis's corners are the
 * track's, so [id] is its CornerId there; else null.
 */
class FlaggableCorner(
    val id: String,
    val name: String,
    val lats: List<Double>,
    val lons: List<Double>,
    val identityRevision: Int?,
) {
    /** Its CornerId in the track's corner identity, or null when the analysis's corners are not the track's. */
    val cornerId: String? get() = id.takeIf { identityRevision != null }
}

/** Each corner of [analysis] with a line on its fastest lap, in driving order; none without an axis. */
fun flaggableCorners(analysis: SessionAnalysis): List<FlaggableCorner> {
    val axis = analysis.axis ?: return emptyList()
    val revision = analysis.identity?.number?.takeIf { analysis.identified }
    return analysis.segments?.segments.orEmpty()
        .filter { it.type == TrackSegmentType.Corner }
        .mapNotNull { corner ->
            segmentLine(axis, corner).takeIf { it.isNotEmpty() }?.let { line ->
                FlaggableCorner(
                    corner.id, corner.name, line.map { it.latitudeDegrees }, line.map { it.longitudeDegrees }, revision,
                )
            }
        }
}
