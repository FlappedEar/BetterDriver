package com.flappedear.betterdriver.video.sync

import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.gpmf.GpsSample
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpsAlignerTest {
    /** A lap-like speed trace in m/s: braking and acceleration every few seconds. */
    private fun speedAt(utcUs: Long): Double {
        val t = utcUs / 1e6
        return 30 + 12 * sin(t / 4.0) + 5 * sin(t / 1.7)
    }

    private val sessionStartUtcUs = 1_760_180_000_000_000L

    /** Session GNSS at 25 Hz for [seconds], session time 0 = [sessionStartUtcUs]. */
    private fun session(seconds: Int, withUtc: Boolean = true) = List(seconds * 25) { i ->
        val t = i * 40_000L
        SpeedPoint(t, speedAt(sessionStartUtcUs + t), if (withUtc) (sessionStartUtcUs + t) / 1000 else null)
    }

    /**
     * Camera GPS at 10 Hz. Its video time 0 is at session time [trueOffsetUs]; its UTC stamps are
     * off by [utcErrorMs], like GoPro GPSU.
     */
    private fun camera(trueOffsetUs: Long, seconds: Int, utcErrorMs: Long = 0, fix: Int = 3) = List(seconds * 10) { i ->
        val v = i * 100_000L
        val utcUs = sessionStartUtcUs + trueOffsetUs + v
        GpsSample(v, utcUs / 1000 + utcErrorMs, 52.0, 19.0, speedAt(utcUs), fix)
    }

    @Test
    fun `speed matching finds the offset even when GPS UTC is off by most of a second`() {
        val trueOffset = 12_345_000L
        val result = assertNotNull(GpsAligner().align(camera(trueOffset, 120, utcErrorMs = 800), session(300)))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000, "offset ${result.sessionOffsetUs}")
        assertTrue(result.rmsSpeedErrorMps!! < 0.2)
    }

    @Test
    fun `a camera started before the session gets a negative offset`() {
        val trueOffset = -7_250_000L
        val result = assertNotNull(GpsAligner().align(camera(trueOffset, 120), session(200)))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000)
    }

    @Test
    fun `without UTC it uses the prior as the centre of the search`() {
        val trueOffset = 60_000_000L
        val prior = AlignmentResult(61_500_000L, Alignment(AlignmentMethod.CAMERA_CLOCK, 2_000_000))
        val cam = camera(trueOffset, 90).map { it.copy(utcMillis = null) }
        val result = assertNotNull(GpsAligner().align(cam, session(300, withUtc = false), prior))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000)
    }

    @Test
    fun `with nothing to go on it searches the whole session`() {
        val trueOffset = 95_000_000L
        val cam = camera(trueOffset, 60).map { it.copy(utcMillis = null) }
        val result = assertNotNull(GpsAligner().align(cam, session(240, withUtc = false)))
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000)
    }

    @Test
    fun `a parked car falls back to the UTC guess`() {
        val ses = List(300 * 25) { i -> SpeedPoint(i * 40_000L, 0.0, (sessionStartUtcUs + i * 40_000L) / 1000) }
        val cam = List(600) { i ->
            GpsSample(i * 100_000L, (sessionStartUtcUs + 5_000_000 + i * 100_000L) / 1000, 0.0, 0.0, 0.0, 3)
        }
        val result = assertNotNull(GpsAligner().align(cam, ses))
        assertEquals(AlignmentMethod.GPS_UTC, result.alignment.method)
        assertEquals(5_000_000, result.sessionOffsetUs)
    }

    @Test
    fun `samples without a fix are ignored`() {
        val cam = camera(0, 60, fix = 0)
        assertNull(GpsAligner().align(cam, session(100)))
    }

    @Test
    fun `no overlap returns the prior`() {
        val prior = AlignmentResult(1, Alignment(AlignmentMethod.COMMAND_ACK, 200_000))
        val cam = camera(0, 60).map { it.copy(utcMillis = null) }
        assertEquals(prior, GpsAligner(searchWindowUs = 1_000_000).align(cam, emptyList(), prior))
    }

    @Test
    fun `a rough camera clock prior widens the search to its uncertainty`() {
        val trueOffset = 40_000_000L
        val prior = AlignmentResult(trueOffset + 25_000_000, Alignment(AlignmentMethod.CAMERA_CLOCK, 60_000_000))
        val cam = camera(trueOffset, 90).map { it.copy(utcMillis = null) }
        val result = assertNotNull(GpsAligner().align(cam, session(300, withUtc = false), prior))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000)
    }

    // Times from damaged or crafted files (audit BD-02): bounded work, never a huge grid or an overflow.

    /** Camera speeds over two minutes, with no UTC. */
    private fun bareCamera() = camera(0, 120).map { SpeedPoint(it.videoTimeUs, it.speedMps) }

    @Test
    fun `two session points ten million seconds apart make no grid of a billion steps`() {
        // Before the bound: DoubleArray(1_000_000_001), 8 GB, ahead of any check.
        val session = listOf(SpeedPoint(0, 20.0), SpeedPoint(10_000_000_000_000L, 20.0))
        assertNull(GpsAligner().alignSpeeds(bareCamera(), session))
    }

    @Test
    fun `extreme and negative times end in no match, not a crash`() {
        val extremes = listOf(Long.MIN_VALUE, -1L, 0L, Long.MAX_VALUE)
        // As GPS UTC too: none of them is a usable time (0 is left out, it would be one).
        val points = extremes.map { SpeedPoint(it, 20.0, it.takeIf { t -> t != 0L }) }
        val session = points
        val prior = AlignmentResult(0, Alignment(AlignmentMethod.CAMERA_CLOCK, 2_000_000))
        val cam = points + bareCamera()
        assertEquals(prior, GpsAligner().alignSpeeds(cam, session, prior))
        assertNull(GpsAligner().alignSpeeds(cam, session))
        val farPrior = AlignmentResult(Long.MAX_VALUE, Alignment(AlignmentMethod.CAMERA_CLOCK, Long.MAX_VALUE))
        assertEquals(farPrior, GpsAligner().alignSpeeds(bareCamera(), session(300, withUtc = false), farPrior))
    }

    @Test
    fun `a stray session point days later does not stop the match`() {
        val trueOffset = 20_000_000L
        val days = 3L * 24 * 3600 * 1_000_000
        val stray = session(300) + SpeedPoint(days, 25.0, (sessionStartUtcUs + days) / 1000)
        val result = assertNotNull(GpsAligner().align(camera(trueOffset, 120), stray))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000, "offset ${result.sessionOffsetUs}")
    }

    @Test
    fun `a two-hour session still synchronises`() {
        val trueOffset = 5_400_000_000L
        val result = assertNotNull(GpsAligner().align(camera(trueOffset, 600), session(7200)))
        assertEquals(AlignmentMethod.GPS_MATCH, result.alignment.method)
        assertTrue(abs(result.sessionOffsetUs - trueOffset) <= 10_000, "offset ${result.sessionOffsetUs}")
    }
}
