package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.analysis.summary.PreviousLaps
import com.flappedear.betterdriver.analysis.summary.SegmentChange
import com.flappedear.betterdriver.analysis.summary.quicker
import com.flappedear.betterdriver.analysis.summary.segmentChanges
import com.flappedear.betterdriver.analysis.summary.slower
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentChangesTest {
    @Test
    fun `the session before is timed through this session's segments`() {
        val session = VboFixture.read("segments_mixed.vbo")
        val analysis = analyzeSession(session)
        // The same laps as the session before: every segment is timed in both, with no change.
        val changes = segmentChanges(analysis, PreviousLaps(session, analysis.laps))
        assertEquals(analysis.segments!!.segments.map { it.id }, changes.map { it.segmentId })
        for (change in changes) {
            assertEquals(0.0, change.deltaSeconds, 1e-9)
            assertEquals(change.spreadSeconds!!, change.referenceSpreadSeconds!!, 1e-9)
        }
        assertTrue(quicker(changes).isEmpty())
        assertTrue(slower(changes).isEmpty())
    }

    @Test
    fun `changes under five hundredths are not quicker or slower`() {
        fun change(id: String, seconds: Double) =
            SegmentChange(id, "Corner $id", TrackSegmentType.Corner, seconds, 5.0, null, null)
        val changes = listOf(change("1", 4.9), change("2", 5.04), change("3", 5.3), change("4", 4.7), change("5", 5.1))
        assertEquals(listOf("4", "1"), quicker(changes).map { it.segmentId })
        assertEquals(listOf("3", "5"), slower(changes).map { it.segmentId })
    }
}
