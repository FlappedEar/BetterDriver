package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.progress.nearestAxisIndex
import com.flappedear.betterdriver.analysis.segments.SegmentLineStepMeters
import com.flappedear.betterdriver.analysis.segments.TrackSegment
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.segments.segmentLine
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SegmentLineTest {
    private fun distance(a: MetricPoint, b: MetricPoint) =
        hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)

    @Test
    fun `a corner's line runs along the axis from its start to its end`() {
        val analysis = analyzeSession(VboFixture.read("segments_mixed.vbo"))
        val axis = assertNotNull(analysis.axis)
        val corners = assertNotNull(analysis.segments).segments.filter { it.type == TrackSegmentType.Corner }
        assertTrue(corners.isNotEmpty())
        for (corner in corners) {
            val line = segmentLine(axis, corner)
            assertTrue(line.size >= 2, "${corner.name}: ${line.size} points")
            val local = line.map { projectCoordinate(it, axis.origin) }
            val start = axis.points[nearestAxisIndex(axis, corner.startProgressMeters)]
            val end = axis.points[nearestAxisIndex(axis, corner.endProgressMeters)]
            assertTrue(distance(local.first(), start) < 0.1)
            assertTrue(distance(local.last(), end) < 0.1)
            for (i in 1 until local.size) {
                val step = distance(local[i], local[i - 1])
                assertTrue(step <= SegmentLineStepMeters + 2 * axis.spacingMeters, "${corner.name}: step $step m")
            }
        }
    }

    @Test
    fun `a segment across the gate wraps, and an invalid axis has no line`() {
        val analysis = analyzeSession(VboFixture.read("segments_mixed.vbo"))
        val axis = assertNotNull(analysis.axis)
        val across = TrackSegment("x", "Across", TrackSegmentType.Corner, axis.lengthMeters - 20.0, 20.0)
        val line = segmentLine(axis, across)
        assertTrue(line.size in 8..10, "${line.size} points")
        assertTrue(segmentLine(ProgressAxis(), across).isEmpty())
    }
}
