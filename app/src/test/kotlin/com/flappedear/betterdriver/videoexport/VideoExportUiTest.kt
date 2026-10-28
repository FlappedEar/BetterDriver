package com.flappedear.betterdriver.videoexport

import com.flappedear.betterdriver.shell.dataVideoDetail
import com.flappedear.betterdriver.timing.live.LapKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportUiTest {
    private fun lap(n: Int, kind: LapKind = LapKind.Timed, best: Boolean = false, onVideo: Boolean = true) =
        LapOption(n, kind, 102_370L, best, onVideo)

    @Test
    fun pickerStartsOnTheBestLapWhenItHasVideo() {
        fun options(vararg laps: LapOption) = VideoExportOptions(60_000_000, laps.toList())
        assertEquals(3, options(lap(1, LapKind.Out), lap(2), lap(3, best = true)).preselected)
        assertEquals(WholeVideo, options(lap(2), lap(3, best = true, onVideo = false)).preselected)
        assertEquals(WholeVideo, options().preselected)
        assertEquals(null, VideoExportOptions(0, listOf(lap(2, onVideo = false))).preselected)
    }

    @Test
    fun lapLinesNameTheLapTimeAndWhatIsSpecial() {
        assertEquals("Lap 3 · 1:42.37" to "Best lap", lapOptionText(lap(3, best = true)))
        assertEquals("Lap 1 · 1:42.37" to "Out lap · No video", lapOptionText(lap(1, LapKind.Out, onVideo = false)))
        assertEquals("Lap 2 · 1:42.37" to "", lapOptionText(lap(2)))
    }

    @Test
    fun pointToPointRunsAreCalledRuns() {
        assertEquals("Run 2 · 1:42.37" to "Best run", lapOptionText(lap(2, best = true), pointToPoint = true))
        assertEquals("Run 1 · 1:42.37" to "No video", lapOptionText(lap(1, onVideo = false), pointToPoint = true))
        assertEquals("BetterDriver-abc-run2.mp4", videoExportFileName("abc", 2, pointToPoint = true))
        assertEquals("BetterDriver-abc-video.mp4", videoExportFileName("abc", WholeVideo, pointToPoint = true))
    }

    @Test
    fun onlyALapHasHandlesAndTheTargetKeepsThem() {
        assertTrue(isSingleLap(7))
        assertFalse(isSingleLap(WholeVideo))
        assertFalse(isSingleLap(RealisticOptimalLap))
        assertFalse(isSingleLap(RawTheoreticalLap))
        assertEquals(listOf(5, 6, 7, 8), LapHandleSecondsChoices)
        assertEquals(6, DefaultLapHandleSeconds)
        assertEquals(VideoExportTarget("a:b", 7, 6), decodeTarget("a:b:7:6"))
        assertEquals(VideoExportTarget("abc", RealisticOptimalLap, 8), decodeTarget("abc:-1:8"))
        assertEquals(null, decodeTarget("abc:7"))
        assertEquals(null, decodeTarget("abc"))
    }

    @Test
    fun wholeVideoLengthInMinutesOrSeconds() {
        assertEquals("18 min of video", wholeVideoText(1_090_000_000))
        assertEquals("45 s of video", wholeVideoText(45_200_000))
    }

    @Test
    fun fileNamesSayWhichPart() {
        assertEquals("BetterDriver-abc-lap7.mp4", videoExportFileName("abc", 7))
        assertEquals("BetterDriver-abc-video.mp4", videoExportFileName("abc", WholeVideo))
    }

    @Test
    fun cardLinesForProgressAndEnds() {
        val running = renderingState(0.42f)
        assertEquals("Rendering video · 42%", running.text)
        assertTrue(running.running)
        assertEquals("Rendering video…", renderingState(null).text)
        assertFalse(endState(VideoExportEnd.Saved).failed)
        assertTrue(endState(VideoExportEnd.Recording).failed)
    }

    @Test
    fun menuLineSaysWhyItIsOff() {
        assertEquals("Not while a session is recording", dataVideoDetail(recording = true, busy = true))
        assertEquals("Another video is being made", dataVideoDetail(recording = false, busy = true))
    }
}
