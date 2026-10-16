package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.ImportOrigin
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * FBBD-229: Arek's RaceBox sends a fix about 0.8 s older than the acceleration in the same message
 * (2026-10-16: GNSS speed changed 0.8 s after the RaceBox's and the phone's accelerometers felt it).
 */
class GnssFixTimeTest {
    private val accelX = ChannelDef(110, "accel_x", "g", "racebox", 3)
    private val accelY = ChannelDef(111, "accel_y", "g", "racebox", 3)
    private val hAcc = ChannelDef(101, "gnss_hacc", "m", "racebox", 2)

    /** Speed in m/s: 20 m/s, braking and accelerating by 8 m/s every 20 s. */
    private fun speed(t: Double) = 20.0 + 8.0 * sin(2 * PI * t / 20.0)

    /** The car's forward acceleration in g, the derivative of [speed]. */
    private fun accel(t: Double) = 8.0 * 2 * PI / 20.0 * cos(2 * PI * t / 20.0) / 9.80665

    /** Small repeatable buzz, as a road gives. */
    private fun buzz(i: Int) = 0.02 * sin(i * 1.7) + 0.01 * cos(i * 3.1)

    /**
     * RaceBox-like sets at 25 Hz for [seconds]: the fix (speed, a position, hAcc) taken [ageSeconds]
     * before the set's stamp, the acceleration at the stamp. [moving] false keeps the car parked.
     */
    private fun sets(ageSeconds: Double, seconds: Int = 300, moving: Boolean = true, accel: Boolean = true) =
        (0 until seconds * 25).map { i ->
            val t = 1.0 + i * 0.04
            val fixAt = t - ageSeconds
            val v = if (moving) speed(fixAt) else 0.0
            SampleSet(
                (t * 1_000_000).toLong(),
                buildList {
                    add(ChannelValue(GnssChannels.LAT.id, 52.0 + fixAt * 1e-5))
                    add(ChannelValue(GnssChannels.LON.id, 17.0))
                    add(ChannelValue(GnssChannels.SPEED_KMH.id, v * 3.6))
                    add(ChannelValue(hAcc.id, 0.3))
                    if (accel) {
                        add(ChannelValue(accelX.id, (if (moving) accel(t) else 0.0) + buzz(i) - 0.05))
                        add(ChannelValue(accelY.id, buzz(i + 7)))
                    }
                },
            )
        }

    private fun session(sets: List<SampleSet>, imported: ImportOrigin? = null) = SessionData(
        id = "20261016-063720-44b75e99",
        info = SessionInfo(1_792_125_440_347L, imported = imported),
        state = SessionState.FINISHED,
        channels = (GnssChannels.ALL + listOf(accelX, accelY, hAcc)).associateBy { it.id },
        sampleSets = sets,
        events = emptyList(),
        end = null,
        truncatedBytes = 0,
        skippedBytes = 0,
    )

    @Test
    fun findsTheFixAgeFromTheAccelerometer() {
        val age = assertNotNull(gnssFixAgeMicros(sets(0.8), setOf(accelX.id, accelY.id)))
        assertTrue(abs(age - 800_000) <= 40_000, "fix age $age µs, expected 0.8 s")
    }

    @Test
    fun aFixOnTimeIsLeftAlone() {
        assertNull(gnssFixAgeMicros(sets(0.0), setOf(accelX.id, accelY.id)))
        val data = session(sets(0.0))
        assertSame(data, data.withGnssAtFixTime())
    }

    @Test
    fun noEstimateWithoutDrivingOrAnAccelerometer() {
        assertNull(gnssFixAgeMicros(sets(0.8, moving = false), setOf(accelX.id, accelY.id)))
        assertNull(gnssFixAgeMicros(sets(0.8, accel = false), setOf(accelX.id, accelY.id)))
        assertNull(gnssFixAgeMicros(sets(0.8, seconds = 30), setOf(accelX.id, accelY.id)), "under a minute of driving")
        val data = session(sets(0.8, accel = false))
        assertSame(data, data.withGnssAtFixTime())
    }

    /** Another app's acceleration may come from GPS or the phone: its timing is the file's own. */
    @Test
    fun anImportedSessionIsLeftAlone() {
        val data = session(sets(0.8), ImportOrigin("RaceChrono CSV", "session.csv", "RaceChrono"))
        assertSame(data, data.withGnssAtFixTime())
    }

    @Test
    fun gnssValuesMoveToTheirFixTimeAndTheAccelerometerStays() {
        val original = sets(0.8)
        val moved = session(original).withGnssAtFixTime().sampleSets
        assertEquals(moved.sortedBy { it.tMicros }, moved, "kept in time order")
        val gnss = moved.filter { s -> s.values.any { it.channelId == GnssChannels.SPEED_KMH.id } }
        val accel = moved.filter { s -> s.values.any { it.channelId == accelX.id } }
        assertEquals(original.size, accel.size)
        assertTrue(gnss.size in original.size - 25..original.size, "only fixes before the start are dropped")
        assertTrue(gnss.all { s -> s.values.none { it.channelId == accelX.id } }, "acceleration is not moved")
        assertTrue(gnss.all { s -> s.values.any { it.channelId == hAcc.id } }, "the receiver's gnss_ channels move too")
        // Each speed now sits at the time it was measured.
        for (s in gnss.drop(100).take(500)) {
            val v = s.values.first { it.channelId == GnssChannels.SPEED_KMH.id }.value / 3.6
            assertEquals(speed(s.tMicros / 1e6), v, 0.25, "speed at ${s.tMicros} µs")
        }
        assertEquals(accel.map { it.tMicros }, original.map { it.tMicros }, "acceleration keeps its stamps")
    }
}
