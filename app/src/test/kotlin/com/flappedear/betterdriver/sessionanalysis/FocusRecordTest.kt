package com.flappedear.betterdriver.sessionanalysis

import com.flappedear.betterdriver.analysis.confidence.Graded
import com.flappedear.betterdriver.analysis.focus.FocusArea
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.FocusChange
import com.flappedear.betterdriver.analysis.focus.FocusMetric
import com.flappedear.betterdriver.analysis.focus.FocusRole
import com.flappedear.betterdriver.analysis.focus.NextFocus
import com.flappedear.betterdriver.analysis.focus.NextSessionFocusAlgorithm
import com.flappedear.betterdriver.analysis.focus.NextSessionPlan
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.FocusChangeRecord
import com.flappedear.betterdriver.recorder.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The next-session focus as kept for the coach and the coaching history (FBBD-20). */
class FocusRecordTest {
    private fun focus(id: String, role: FocusRole, seconds: Double?, changes: List<FocusChange> = emptyList()) =
        NextFocus(
            role = role,
            area = FocusArea(
                kind = FocusAreaKind.RepeatedLoss,
                segmentId = id,
                name = "Corner $id",
                observation = "In 4 of 5 compared laps you lost time through Corner $id.",
                hypothesis = "",
                metric = "medianLossSeconds",
                value = seconds ?: 0.0,
                unit = "s",
                sampleCount = 4,
                lap = 3,
                against = 2,
                score = 1.0,
            ),
            confidence = Graded(if (role == FocusRole.Primary) Confidence.HIGH else Confidence.MEDIUM),
            timeAvailableSeconds = seconds,
            timeConfidence = if (role == FocusRole.Primary) Confidence.HIGH else Confidence.MEDIUM,
            changes = changes,
            fasterLaps = 2,
            otherLaps = 3,
            what = "Your 2 fastest passes braked 8 m later than your other 3.",
        )

    private val line = CornerPick("Corner c4", listOf(50.0, 50.0001), listOf(19.0, 19.0001), "s1")

    @Test
    fun `each focus is kept under its CornerId, primary first, with its evidence and line`() {
        val later = FocusChange(FocusMetric.BrakingPoint, 120.0, 128.0, "m")
        val plan = NextSessionPlan(
            primary = focus("c4", FocusRole.Primary, 0.18, listOf(later)),
            secondary = listOf(focus("c2", FocusRole.Secondary, 0.07)),
            identityRevision = 3,
        )
        val record = focusRecord("track-1", "s1", 1_000L, plan, mapOf("c4" to line))
        assertEquals("track-1", record.trackId)
        assertEquals("s1", record.sessionId)
        assertEquals(1_000L, record.sessionStartUtcMillis)
        assertEquals(NextSessionFocusAlgorithm, record.algorithm)
        assertEquals(listOf("s1/c4", "s1/c2"), record.targets.map { it.id })
        assertEquals(listOf("primary", "secondary"), record.targets.map { it.role })
        assertEquals(listOf("c4", "c2"), record.targets.map { it.cornerId })
        assertEquals(listOf(3, 3), record.targets.map { it.identityRevision })
        assertEquals(listOf("HIGH", "MEDIUM"), record.targets.map { it.confidence })
        assertEquals(listOf("repeatedLoss", "repeatedLoss"), record.targets.map { it.kind })
        val primary = record.targets[0]
        assertEquals(0.18, primary.timeAvailableSeconds!!, 1e-9)
        assertEquals(listOf(FocusChangeRecord("brakingPoint", 120.0, 128.0, "m", true, "HIGH")), primary.changes)
        assertEquals(listOf("HIGH", "MEDIUM"), record.targets.map { it.timeConfidence })
        assertEquals(line.lats, primary.lats)
        // The coach places the primary by its CornerId; a focus without a line is kept but not handed on.
        assertEquals(listOf("c4"), record.picks().map { it.cornerId })
        assertTrue(record.targets[1].lats.isEmpty())
    }

    @Test
    fun `without the track's corner identity a focus keeps the analysis's own id only`() {
        val plan = NextSessionPlan(primary = focus("s3", FocusRole.Primary, 0.2))
        val target = focusRecord("t", "s1", 0L, plan, emptyMap()).targets.single()
        assertEquals("s3", target.segmentId)
        assertNull(target.cornerId)
        assertNull(target.identityRevision)
    }

    @Test
    fun `nothing clear enough is kept as a record without targets`() {
        assertTrue(focusRecord("t", "s1", 0L, NextSessionPlan(), emptyMap()).targets.isEmpty())
    }
}
