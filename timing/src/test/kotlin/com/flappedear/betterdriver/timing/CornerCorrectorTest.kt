@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.ChangedMeanwhileNote
import com.flappedear.betterdriver.timing.track.CornerCorrector
import com.flappedear.betterdriver.timing.track.CorrectedNote
import com.flappedear.betterdriver.timing.track.FoundNote
import com.flappedear.betterdriver.timing.track.NoCornersNote
import com.flappedear.betterdriver.timing.track.SaveFirstNote
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.SegmentKind
import com.flappedear.betterdriver.timing.track.SegmentShape
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.corrected
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The corner corrections of the iPad and iPhone track editor (FBBD-366), as Android's (FBBD-179). */
class CornerCorrectorTest {
    private val circuit = FakeCircuit()
    private val bend = PI * circuit.radius
    private val bend1 = 200.0..200.0 + bend
    private val bend2 = bend1.endInclusive + 400.0..bend1.endInclusive + 400.0 + bend

    private val detected = TrackRevision.first(
        circuit.frame,
        (0 until (circuit.length / 2).toInt()).map { circuit.xy(it * 2.0) },
        closed = true,
        listOf(
            SegmentShape(SegmentKind.Corner, bend1.start, bend1.endInclusive),
            SegmentShape(SegmentKind.Straight, bend1.endInclusive, bend2.start),
            SegmentShape(SegmentKind.Corner, bend2.start, bend2.endInclusive),
            SegmentShape(SegmentKind.Straight, bend2.endInclusive, bend1.start),
        ),
    )

    @Test
    fun `the list and its note follow the corners and whether the track is saved`() {
        assertEquals(NoCornersNote, CornerCorrector(null).listNote)
        assertTrue(CornerCorrector(null).rows().isEmpty())
        val unsaved = CornerCorrector(detected, correctable = false)
        assertEquals(SaveFirstNote, unsaved.listNote)
        unsaved.select("c1")
        assertNull(unsaved.segmentId, "nothing is corrected before the track is saved")
        val c = CornerCorrector(detected)
        assertEquals(FoundNote, c.listNote)
        assertEquals(listOf("Corner 1", "Straight 1", "Corner 2", "Straight 2"), c.rows().map { it.name })
        assertEquals(listOf(false, true, false, true), c.rows().map { it.straight })
        assertFalse(c.canRestore)
        unsaved.saved(detected)
        assertTrue(unsaved.correctable)
    }

    @Test
    fun `a tap near the track picks the segment there, one far away none`() {
        val c = CornerCorrector(detected)
        c.selectAt(circuit.geo((bend2.start + bend2.endInclusive) / 2))
        assertEquals("c2", c.segmentId)
        assertEquals("Corner 2", c.selectedSegment?.name)
        c.selectAt(circuit.frame.toGeo(Xy(5000.0, 5000.0)))
        assertNull(c.segmentId)
    }

    @Test
    fun `a control's edit is stored and shown, the chips say what can change`() {
        val c = CornerCorrector(detected)
        c.select("c1")
        val chips = c.typeChips()
        assertEquals(listOf("Corner", "Chicane", "Straight"), chips.map { it.label })
        assertEquals(listOf(true, false, false), chips.map { it.selected })
        assertTrue(chips.all { it.enabled })
        val split = assertNotNull(c.splitEdit())
        val started = assertNotNull(c.startCorrection(split))
        assertTrue(c.correcting)
        assertNull(c.ifApplies(assertNotNull(c.mergeEdit())), "nothing else while one is stored")
        val stored = assertNotNull(detected.corrected(started))
        c.correctionStored(stored, applied = true)
        assertFalse(c.correcting)
        assertEquals(listOf("Corner 1", "Corner 2", "Straight 1", "Corner 3", "Straight 2"), c.rows().map { it.name })
        assertEquals(CorrectedNote, c.listNote)
        assertTrue(c.canRestore)
        assertTrue(c.rows().first { it.id == "c1" }.edited)
        assertTrue(c.applies(c.restoreEdit()))
        // A name of the driver's own: its number beside it.
        c.correctionStored(stored.corrected(assertNotNull(c.renameEdit("Hairpin"))), applied = true)
        assertEquals("Corner 1", c.rows().first { it.id == "c1" }.number)
        assertFalse(c.validName("x".repeat(41)))
    }

    @Test
    fun `a correction the stored corners did not take shows them as they are`() {
        val c = CornerCorrector(detected)
        c.select("c2")
        c.startCorrection(assertNotNull(c.removeEdit()))
        c.correctionStored(detected, applied = false)
        assertEquals(ChangedMeanwhileNote, c.correctionNote)
        assertEquals("c2", c.segmentId)
        c.correctionStored(null, applied = false)
        assertNull(c.segmentId)
        assertTrue(c.strokes().isEmpty())
    }

    @Test
    fun `an end dragged along the track stops short of the neighbours and moves the boundary when let go`() {
        val c = CornerCorrector(detected)
        c.select("c2")
        assertEquals(listOf(true, false), c.handles().map { it.start })
        // Dragged to 30 m before the corner: the start moves there.
        c.drag(start = true, to = circuit.geo(bend2.start - 30.0))
        assertEquals(bend2.start - 30.0, assertNotNull(c.dragged).meters, 1.0)
        // Past the start of the straight before it: kept 10 m after it.
        c.drag(start = true, to = circuit.geo(bend1.endInclusive - 50.0))
        assertEquals(bend1.endInclusive + 10.0, assertNotNull(c.dragged).meters, 1.0)
        val drawn = c.strokes().last()
        assertTrue(drawn.selected && drawn.id == "c2", "the corrected one is drawn last")
        assertEquals("2", drawn.number)
        val edit = assertNotNull(c.endDrag()) as SegmentEdit.MoveBoundary
        assertEquals("c2", edit.id)
        assertTrue(c.correcting)
        // The end of the corner is the start of the straight after it.
        c.correctionStored(detected.corrected(edit), applied = true)
        c.drag(start = false, to = circuit.geo(bend2.endInclusive + 40.0))
        assertEquals("s2", (assertNotNull(c.endDrag()) as SegmentEdit.MoveBoundary).id)
    }
}
