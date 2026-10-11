@file:Suppress("LongParameterList")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.corners.CornerLapObservation
import com.flappedear.betterdriver.analysis.focus.FocusArea
import com.flappedear.betterdriver.analysis.focus.FocusAreaKind
import com.flappedear.betterdriver.analysis.focus.FocusCorner
import com.flappedear.betterdriver.analysis.focus.FocusInputs
import com.flappedear.betterdriver.analysis.focus.FocusLoss
import com.flappedear.betterdriver.analysis.focus.FocusSectorGap
import com.flappedear.betterdriver.analysis.focus.selectFocusAreas
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Areas to inspect next, ported from FlappedEar Telemetry's test/analysis/focus_areas_test.dart
 * (itself from Overlays' FocusAreasTests.cpp): chosen from computed observations only, observation
 * separate from hypothesis, no causal or "brake later" claims, evidence on every area.
 */
class FocusAreasTest {
    private fun lap(name: String) = mapOf("runId" to name)

    private fun loss(segment: String, seconds: Double, lapName: String) =
        FocusLoss(segment, "Corner $segment", seconds, lap(lapName))

    private fun gap(id: String, name: String, seconds: Double, label: String, source: String, sourceLabel: String) =
        FocusSectorGap(id, name, seconds, lap("best"), label, lap(source), sourceLabel)

    private fun braking(meters: Double, provenance: String, lapName: String, minimumSpeed: Double = 80.0) =
        CornerLapObservation(
            lapReference = lap(lapName),
            brakingPointMeters = meters,
            brakingProvenance = provenance,
            minimumSpeed = minimumSpeed,
        )

    // Words that would turn an observation into advice or a causal claim.
    private fun expectNoAdviceWording(area: FocusArea) {
        for (text in listOf(area.observation, area.hypothesis)) {
            for (word in listOf("brake later", "should", "because you", "you must", "caused", "guarantee")) {
                assertFalse(text.lowercase().contains(word), text)
            }
        }
        assertTrue(area.observation.isNotEmpty())
        assertTrue(area.hypothesis.isNotEmpty())
        assertNotNull(area.lap)
        assertNotNull(area.against)
    }

    @Test
    fun `selects one of each kind first`() {
        val inputs = FocusInputs(
            referenceLap = lap("best"),
            referenceLabel = "Session 5 · LAP 2",
            comparedLapCount = 5,
            gaps = listOf(
                gap("7", "Corner 7", 0.412, "Session 5 · LAP 2", "s3l2", "Session 3 · LAP 2"),
                gap("8", "Corner 8", 0.2, "Session 5 · LAP 2", "s4l1", "Session 4 · LAP 1"),
            ),
            // Corner 2 loses on four of five laps (median 0.3); corner 7 is taken by the gap.
            losses = listOf(
                loss("2", 0.25, "a"), loss("2", 0.30, "b"), loss("2", 0.31, "c"), loss("2", 0.6, "d"),
                loss("7", 0.5, "a"), loss("7", 0.5, "b"), loss("7", 0.5, "c"),
            ),
            corners = listOf(
                FocusCorner(
                    "4",
                    "Corner 4",
                    listOf(
                        braking(100.0, "measured", "a"), braking(120.0, "measured", "b"),
                        braking(140.0, "measured", "c"), braking(160.0, "measured", "d"),
                    ),
                ),
            ),
        )
        val areas = selectFocusAreas(inputs)
        assertEquals(3, areas.size)
        assertEquals(FocusAreaKind.SectorGap, areas[0].kind)
        assertEquals("7", areas[0].segmentId)
        assertTrue(areas[0].observation.contains("0.412 s"))
        assertTrue(areas[0].observation.contains("Session 3 · LAP 2"))
        assertEquals(lap("s3l2"), areas[0].against)
        assertEquals(FocusAreaKind.RepeatedLoss, areas[1].kind)
        assertEquals("2", areas[1].segmentId) // corner 7 already has an area
        assertEquals(4, areas[1].sampleCount)
        assertTrue(areas[1].observation.contains("In 4 of 5 compared laps"))
        assertEquals(inputs.referenceLap, areas[1].against)
        assertTrue(areas[1].lap in listOf(lap("b"), lap("c"))) // the typical lap, near the median
        assertEquals(FocusAreaKind.BrakingSpread, areas[2].kind)
        areas.forEach(::expectNoAdviceWording)
    }

    @Test
    fun `requires repeats and thresholds`() {
        val inputs = FocusInputs(
            referenceLap = lap("best"),
            referenceLabel = "best",
            comparedLapCount = 5,
            // Two losses are not a pattern; tiny losses and gaps are not areas.
            losses = listOf(
                loss("2", 0.4, "a"), loss("2", 0.4, "b"),
                loss("3", 0.01, "a"), loss("3", 0.02, "b"), loss("3", 0.03, "c"),
            ),
            gaps = listOf(gap("9", "Corner 9", 0.01, "best", "x", "x")),
            corners = listOf(
                FocusCorner(
                    "4",
                    "Corner 4",
                    listOf(
                        braking(100.0, "measured", "a"), braking(102.0, "measured", "b"),
                        braking(104.0, "measured", "c"),
                    ),
                ),
            ),
        )
        assertTrue(selectFocusAreas(inputs).isEmpty())
    }

    @Test
    fun `keeps measured braking apart and makes no braking claim`() {
        // Inferred braking points spread widely but are never mixed with measured ones.
        val mixed = FocusCorner(
            "4",
            "Corner 4",
            listOf(
                braking(100.0, "measured", "a"), braking(101.0, "measured", "b"), braking(102.0, "measured", "c"),
                braking(40.0, "inferred", "d"), braking(200.0, "inferred", "e"),
            ),
        )
        assertTrue(selectFocusAreas(FocusInputs(corners = listOf(mixed))).isEmpty())
        val spread = FocusCorner(
            "4",
            "Corner 4",
            listOf(
                braking(100.0, "measured", "early"), braking(115.0, "measured", "b"),
                braking(130.0, "measured", "c"), braking(150.0, "measured", "late"),
            ),
        )
        val areas = selectFocusAreas(FocusInputs(corners = listOf(spread)))
        assertEquals(1, areas.size)
        assertEquals("m", areas[0].unit)
        assertTrue(areas[0].observation.contains("measured from the brake signal"))
        assertTrue(areas[0].hypothesis.contains("does not show whether earlier or later braking is faster or safe"))
        assertEquals(lap("early"), areas[0].lap)
        assertEquals(lap("late"), areas[0].against)
        expectNoAdviceWording(areas[0])
        // A minimum-speed spread (8 of 80 km/h) with a steady braking point.
        val speed = selectFocusAreas(
            FocusInputs(
                speedUnit = "km/h",
                corners = listOf(
                    FocusCorner(
                        "5",
                        "Corner 5",
                        listOf(
                            braking(100.0, "measured", "a", minimumSpeed = 70.0),
                            braking(101.0, "measured", "b", minimumSpeed = 78.0),
                            braking(102.0, "measured", "c", minimumSpeed = 86.0),
                            braking(103.0, "measured", "d", minimumSpeed = 94.0),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(1, speed.size)
        assertEquals(FocusAreaKind.MinimumSpeedSpread, speed[0].kind)
        assertTrue(speed[0].observation.contains("km/h"))
        assertTrue(speed[0].hypothesis.contains("not by itself better"))
        expectNoAdviceWording(speed[0])
    }

    @Test
    fun `is short, deterministic and empty without observations`() {
        assertTrue(selectFocusAreas(FocusInputs()).isEmpty())
        val inputs = FocusInputs(
            referenceLap = lap("best"),
            gaps = (0 until 8).map { gap("$it", "Corner $it", 0.1 + 0.01 * it, "best", "x", "x") },
        )
        val areas = selectFocusAreas(inputs)
        assertEquals(3, areas.size)
        assertEquals("7", areas[0].segmentId) // largest gap first
        assertEquals("6", areas[1].segmentId)
        assertEquals(5, selectFocusAreas(inputs, maximum = 5).size)
        // Equal scores order by segment id, whatever the input order.
        val tied = FocusInputs(
            gaps = listOf(gap("b", "B", 0.2, "best", "x", "x"), gap("a", "A", 0.2, "best", "x", "x")),
        )
        assertEquals("a", selectFocusAreas(tied)[0].segmentId)
    }
}
