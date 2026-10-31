package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.analysis.optimal.OptimalKind
import com.flappedear.betterdriver.analysis.optimal.OptimalLap
import com.flappedear.betterdriver.analysis.optimal.OptimalLapResult
import com.flappedear.betterdriver.analysis.optimal.OptimalLapUnavailable
import com.flappedear.betterdriver.analysis.optimal.OptimalPart
import com.flappedear.betterdriver.analysis.optimal.PartOnVideo
import com.flappedear.betterdriver.analysis.optimal.kindDescription
import com.flappedear.betterdriver.analysis.optimal.kindName
import com.flappedear.betterdriver.analysis.optimal.missingText
import com.flappedear.betterdriver.analysis.optimal.optimalClipPlan
import com.flappedear.betterdriver.analysis.optimal.optimalOption
import com.flappedear.betterdriver.analysis.optimal.optimalOptionText
import com.flappedear.betterdriver.analysis.optimal.optimalPartText
import com.flappedear.betterdriver.feature.analysis.lapsText
import com.flappedear.betterdriver.recorder.video.VideoClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimalVideoUiTest {
    // Three parts at telemetry 10-30 s (lap 2), 30-52 s (lap 1) and 52-70 s (lap 4); 60 s in all.
    private fun part(name: String, lap: Int, from: Double, to: Double, offset: Double) =
        OptimalPart(name, name, name.startsWith("Corner"), lap, from, to, offset, null)

    private val lap = OptimalLap(
        listOf(
            part("Straight 1", 2, 10.0, 30.0, 0.0),
            part("Corner 1", 1, 30.0, 52.0, 20.0),
            part("Straight 2", 4, 52.0, 70.0, 42.0),
        ),
        totalSeconds = 60.0,
        fastestLapNumber = 1,
    )

    private fun clip(from: Double, to: Double) = VideoClip("phone", "a.mp4", (from * 1e6).toLong(), (to * 1e6).toLong())

    private fun plan(vararg clips: VideoClip) =
        optimalClipPlan(lap, clips.toList(), { c -> c.endMicros?.let { it - c.startMicros } }, originMicros = 0L)

    @Test
    fun wholeOnVideoNamesTheLaps() {
        val o = optimalOption(Raw, OptimalLapResult(lap), plan(clip(0.0, 100.0)))
        assertTrue(o.enabled)
        assertEquals(60_000L, o.totalMillis)
        assertEquals(
            "Raw theoretical best · 1:00.00" to
                "Fastest of each corner from laps 1, 2 and 4, joined whatever the joint",
            optimalOptionText(o),
        )
        assertNull(missingText(o))
        assertEquals("Lap 1 · 0:22.00" to null, optimalPartText(o.parts[1]))
    }

    @Test
    fun partsWithoutVideoAreListedBeforeTheRender() {
        // Video from 25 s to 60 s: Straight 1 partly, Corner 1 whole, Straight 2 partly; then from 40 s only.
        val partly = optimalOption(Raw, OptimalLapResult(lap), plan(clip(25.0, 60.0)))
        assertEquals(listOf(PartOnVideo.Partly, PartOnVideo.Whole, PartOnVideo.Partly), partly.parts.map { it.video })
        assertEquals("Partly on video: Straight 1 (lap 2), Straight 2 (lap 4)", missingText(partly))

        val missing = optimalOption(Raw, OptimalLapResult(lap), plan(clip(30.0, 100.0)))
        assertTrue(missing.enabled)
        assertEquals("No video, skipped: Straight 1 (lap 2)", missingText(missing))
        assertEquals(
            "Fastest of each corner from laps 1, 2 and 4, joined whatever the joint · " +
                "No video, skipped: Straight 1 (lap 2)",
            optimalOptionText(missing).second,
        )
        assertEquals("Lap 2 · 0:20.00" to "No video", optimalPartText(missing.parts[0]))
    }

    @Test
    fun disabledWithAReason() {
        val none = optimalOption(Raw, OptimalLapResult(lap), plan(clip(200.0, 300.0)))
        assertFalse(none.enabled)
        assertEquals(
            "Raw theoretical best · 1:00.00" to "No part of the raw theoretical best is on video.",
            optimalOptionText(none),
        )
        assertEquals("This session has no video.", optimalOption(Raw, OptimalLapResult(lap), null).unavailable)
        assertEquals(3, optimalOption(Raw, OptimalLapResult(lap), null).parts.size)
        val noLaps = optimalOption(Raw, null, null)
        assertEquals(
            "Raw theoretical best" to "No raw theoretical best: the session has no counted laps at a saved track.",
            optimalOptionText(noLaps),
        )
        val noSegments = OptimalLapResult(why = OptimalLapUnavailable.NoSegments)
        assertEquals(
            "No raw theoretical best: the corners could not be found on the best lap.",
            optimalOption(Raw, noSegments, null).unavailable,
        )
        val incomplete = OptimalLapResult(why = OptimalLapUnavailable.IncompleteCoverage)
        assertEquals(
            "No raw theoretical best: a corner has no fully timed pass on any counted lap.",
            optimalOption(Raw, incomplete, null).unavailable,
        )
    }

    @Test
    fun lapListsReadNaturally() {
        assertEquals("lap 4", lapsText(listOf(4)))
        assertEquals("laps 2 and 4", lapsText(listOf(2, 4)))
        assertEquals("laps 2, 4 and 7", lapsText(listOf(2, 4, 7)))
        assertEquals("BetterDriver-abc-realistic-optimal.mp4", videoExportFileName("abc", RealisticOptimalLap))
        assertEquals("BetterDriver-abc-raw-theoretical.mp4", videoExportFileName("abc", RawTheoreticalLap))
    }

    @Test
    fun theRealisticOptimalIsNamedApartFromTheRawTheoreticalBest() {
        val realistic = lap.copy(kind = Realistic)
        val o = optimalOption(Realistic, OptimalLapResult(realistic), plan(clip(0.0, 100.0)))
        assertEquals(
            "Realistic optimal · 1:00.00" to "Compatible corners and straights from laps 1, 2 and 4",
            optimalOptionText(o),
        )
        val one = optimalOption(Realistic, OptimalLapResult(realistic.copy(parts = realistic.parts.take(1))), null)
        assertEquals("No compatible combination beats lap 2", kindDescription(Realistic, one.laps))
        val noChain = OptimalLapResult(why = OptimalLapUnavailable.NoCompatibleChain)
        assertEquals(
            "No realistic optimal: no counted laps join compatibly through every corner.",
            optimalOption(Realistic, noChain, null).unavailable,
        )
        // The three names never meet: no screen calls either composite plain "optimal".
        assertEquals(setOf("Realistic optimal", "Raw theoretical best"), OptimalKind.entries.map(::kindName).toSet())
        assertEquals(RealisticOptimalLap, videoPartOf(Realistic))
        assertEquals(Raw, optimalKindOf(RawTheoreticalLap))
        assertEquals(null, optimalKindOf(WholeVideo))
    }

    @Test
    fun theSheetStartsOnTheRealisticOptimalUnlessOnlyTheRawOneCanBeMade() {
        val realistic = optimalOption(Realistic, OptimalLapResult(lap.copy(kind = Realistic)), plan(clip(0.0, 100.0)))
        val raw = optimalOption(Raw, OptimalLapResult(lap), plan(clip(0.0, 100.0)))
        assertEquals(Realistic, CompositeOptions(realistic, raw).preselected.kind)
        val none = optimalOption(Realistic, OptimalLapResult(why = OptimalLapUnavailable.NoCompatibleChain), null)
        assertEquals(Raw, CompositeOptions(none, raw).preselected.kind)
        assertTrue(CompositeOptions(none, raw).enabled)
    }
}

private val Raw = OptimalKind.RawTheoreticalBest
private val Realistic = OptimalKind.RealisticOptimal
