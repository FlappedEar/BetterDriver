package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.timing.overlay.CutPosition
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.outputMicrosAt
import com.flappedear.betterdriver.timing.overlay.positionAt
import com.flappedear.betterdriver.timing.overlay.sessionMicrosAt
import com.flappedear.betterdriver.timing.overlay.totalMicros
import com.flappedear.betterdriver.timing.overlay.videoCuts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Watching a session's whole video with the data overlay (FBBD-341): the player plays the cuts of
 * the whole video one after the other, and the overlay looks the data up by the session time of the
 * frame on screen. Also for a drive with no track, so no laps.
 */
class WatchVideoTest {
    /** Two phone clips with 2 s between them the camera missed (a new file), 60 s and 30 s long. */
    private val clips = listOf(
        VideoClip("phone", "a.mp4", 5_000_000, null),
        VideoClip("phone", "b.mp4", 67_000_000, null),
    )
    private val durations = mapOf("a.mp4" to 60_000_000L, "b.mp4" to 30_000_000L)
    private val cuts = videoCuts(clips, { durations[it.file] }, Long.MIN_VALUE, Long.MAX_VALUE)

    @Test
    fun `the whole video plays both clips back to back`() {
        assertEquals(listOf("a.mp4", "b.mp4"), cuts.map { it.clip.file })
        assertEquals(90_000_000L, cuts.totalMicros())
    }

    @Test
    fun `a place on the seek bar is a cut and a time into it, and back`() {
        assertEquals(CutPosition(0, 0), cuts.positionAt(0))
        assertEquals(CutPosition(0, 59_000_000), cuts.positionAt(59_000_000))
        assertEquals(CutPosition(1, 0), cuts.positionAt(60_000_000))
        assertEquals(CutPosition(1, 10_000_000), cuts.positionAt(70_000_000))
        // Off either end: the first frame, the last cut's end.
        assertEquals(CutPosition(0, 0), cuts.positionAt(-1))
        assertEquals(CutPosition(1, 30_000_000), cuts.positionAt(500_000_000))
        for (t in listOf(0L, 12_345_678L, 60_000_000L, 89_999_999L)) {
            assertEquals(t, cuts.outputMicrosAt(assertNotNull(cuts.positionAt(t))))
        }
    }

    @Test
    fun `the overlay shows the session time of the frame, after the gap too`() {
        assertEquals(5_000_000L, cuts.sessionMicrosAt(CutPosition(0, 0)))
        assertEquals(35_000_000L, cuts.sessionMicrosAt(CutPosition(0, 30_000_000)))
        // The second file starts at 67 s of the session, not at 65 s where the first one ended.
        assertEquals(67_000_000L, cuts.sessionMicrosAt(CutPosition(1, 0)))
        assertEquals(77_000_000L, cuts.sessionMicrosAt(CutPosition(1, 10_000_000)))
    }

    @Test
    fun `no video, no place`() {
        val none = videoCuts(emptyList(), { null }, Long.MIN_VALUE, Long.MAX_VALUE)
        assertNull(none.positionAt(0))
        assertNull(cuts.sessionMicrosAt(CutPosition(2, 0)))
        assertNull(cuts.outputMicrosAt(CutPosition(-1, 0)))
    }

    @Test
    fun `a drive with no track shows speed, map position and G, and no lap`() {
        // A straight drive north at 50 km/h, one fix per 100 ms for 90 s, the video covering it.
        val sets = (0..900).map { i ->
            val t = i * 100_000L
            SampleSet(
                t,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, 52.0 + i * 1.25e-5),
                    ChannelValue(GnssChannels.LON.id, 21.0),
                    ChannelValue(GnssChannels.SPEED_KMH.id, 50.0),
                ),
            )
        }
        val data = OverlayData(sets, laps = null)
        assertTrue(data.outline.size >= 2, "the map shows the driven path")
        val at = assertNotNull(cuts.sessionMicrosAt(assertNotNull(cuts.positionAt(70_000_000))))
        val f = data.at(at)
        assertEquals(50.0, assertNotNull(f.speedKmh), 0.5)
        assertNotNull(f.position)
        assertNull(f.lap)
    }
}
