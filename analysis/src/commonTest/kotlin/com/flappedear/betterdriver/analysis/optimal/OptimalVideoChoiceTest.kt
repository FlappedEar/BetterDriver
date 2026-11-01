package com.flappedear.betterdriver.analysis.optimal

import com.flappedear.betterdriver.recorder.video.VideoClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composite laps' video as both apps offer it (FBBD-370): the same choice and the same words on
 * the JVM (Android) and on the iOS simulator.
 */
class OptimalVideoChoiceTest {
    private fun part(name: String, lap: Int, from: Double, to: Double, offset: Double) =
        OptimalPart(name, name, name.startsWith("Corner"), lap, from, to, offset, null)

    private val lap = OptimalLap(
        listOf(part("Straight 1", 2, 10.0, 30.0, 0.0), part("Corner 1", 1, 30.0, 52.0, 20.0)),
        totalSeconds = 42.0,
        fastestLapNumber = 1,
    )

    private val clips = listOf(VideoClip("phone", "a.mp4", 0L, 100_000_000L))
    private val lengths: (VideoClip) -> Long? = { c -> c.endMicros?.let { it - c.startMicros } }

    @Test
    fun noAnalysisNoCompositeLaps() {
        val films = optimalVideo(null, clips, 0L, lengths)
        for (film in listOf(films.realistic, films.raw)) {
            assertNull(film.result)
            assertNull(film.plan)
            assertFalse(film.option.enabled)
        }
        assertEquals(
            "No realistic optimal: the session has no counted laps at a saved track.",
            films.realistic.option.unavailable,
        )
        assertEquals(OptimalKind.RawTheoreticalBest, films.of(OptimalKind.RawTheoreticalBest).kind)
        assertEquals(OptimalKind.RealisticOptimal, OptimalVideo.None.of(OptimalKind.RealisticOptimal).kind)
    }

    @Test
    fun aLapOnVideoIsOfferedWithItsParts() {
        val plan = optimalClipPlan(lap, clips, lengths, originMicros = 0L)
        val film = OptimalFilm(OptimalKind.RawTheoreticalBest, OptimalLapResult(lap), plan)
        assertTrue(film.option.enabled)
        assertEquals(
            "Raw theoretical best · 0:42.00" to "Fastest of each corner from laps 1 and 2, joined whatever the joint",
            optimalOptionText(film.option),
        )
        val noVideo = OptimalFilm(OptimalKind.RawTheoreticalBest, OptimalLapResult(lap), null).option
        assertEquals("This session has no video.", noVideo.unavailable)
        assertEquals(listOf(PartOnVideo.None, PartOnVideo.None), noVideo.parts.map { it.video })
    }

    @Test
    fun theButtonUnderTheLapTimesSaysTheSameOnBothApps() {
        assertEquals("Finding the parts on video…", optimalVideoNote(null))
        val plan = optimalClipPlan(lap, clips, lengths, originMicros = 0L)
        val realistic = OptimalFilm(OptimalKind.RealisticOptimal, OptimalLapResult(lap), plan)
        val raw = OptimalFilm(OptimalKind.RawTheoreticalBest, OptimalLapResult(lap), plan)
        val options = compositeOptions(OptimalVideo(realistic, raw))
        assertTrue(options.enabled)
        assertEquals(OptimalKind.RealisticOptimal, options.preselected.kind)
        assertEquals("Every part on video, from laps 1 and 2", optimalVideoNote(options))
        val noVideo = compositeOptions(optimalVideo(null, null, 0L, lengths))
        assertFalse(noVideo.enabled)
        assertEquals(noVideo.realistic.unavailable, optimalVideoNote(noVideo))
        val unreadable = unreadableCompositeOptions()
        assertEquals(UnreadableVideoText, optimalVideoNote(unreadable))
        assertEquals(OptimalKind.RawTheoreticalBest, unreadable.of(OptimalKind.RawTheoreticalBest).kind)
    }
}
