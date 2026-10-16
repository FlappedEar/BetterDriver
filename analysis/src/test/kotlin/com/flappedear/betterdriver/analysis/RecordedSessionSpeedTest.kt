package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertNotNull

/**
 * A half-hour drive as Arek records it opens quickly. 0.3.19 averaged in the phone's accelerometer,
 * which records about 110 samples a second, and the analysis compared every axis's times at every
 * sample and searched every lag window by window: on the 2026-10-16 drive the lap analysis that used
 * to open at once took ages.
 */
class RecordedSessionSpeedTest {
    private val minutes = 30
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(50.0 * PI / 180.0)

    /** Speeding up, braking and turning: the RaceBox at 25 Hz mounted backwards, the phone at 109.3 Hz. */
    private fun drive(): SessionData {
        val random = Random(3)
        val receiver = listOf("accel_x", "accel_y", "accel_z")
            .mapIndexed { k, n -> ChannelDef(110 + k, n, "g", "racebox", 3) }
        val phone = listOf("phone_accel_x", "phone_accel_y", "phone_accel_z")
            .mapIndexed { k, n -> ChannelDef(700 + k, n, "g", "phone", 3) }
        fun along(t: Double) = 0.3 * sin(t / 7.0)
        fun yawRate(t: Double) = 0.15 * sin(t / 11.0)
        val sets = ArrayList<SampleSet>()
        var east = 0.0
        var north = 0.0
        var heading = 0.0
        var speed = 20.0
        for (i in 0 until minutes * 60 * 25) {
            val t = i * 0.04
            speed = (speed + along(t) * 9.80665 * 0.04).coerceIn(5.0, 45.0)
            heading += yawRate(t) * 0.04
            east += speed * cos(heading) * 0.04
            north += speed * sin(heading) * 0.04
            val noise = { 0.02 * random.nextDouble() }
            sets += SampleSet(
                (t * 1e6).toLong(),
                listOf(
                    ChannelValue(GnssChannels.LAT.id, 50.0 + north / metersPerDegreeLat),
                    ChannelValue(GnssChannels.LON.id, 19.0 + east / metersPerDegreeLon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ChannelValue(110, -along(t) + noise()),
                    ChannelValue(111, speed * yawRate(t) / 9.80665 + noise()),
                    ChannelValue(112, 1.0 + noise()),
                ),
            )
        }
        for (i in 0 until (minutes * 60 * 109.3).toInt()) {
            val t = i / 109.3 + 0.013
            sets += SampleSet(
                (t * 1e6).toLong(),
                listOf(
                    ChannelValue(700, 0.05 * random.nextDouble()),
                    ChannelValue(701, -along(t) + 0.05 * random.nextDouble()),
                    ChannelValue(702, 1.0 + 0.05 * random.nextDouble()),
                ),
            )
        }
        val defs = GnssChannels.ALL + receiver + phone
        return SessionData(
            "s", SessionInfo(startUtcMillis = 0, channels = defs), SessionState.FINISHED,
            defs.associateBy { it.id }, sets, emptyList(), null, 0, 0,
        )
    }

    // Before the fix, comparing the phone's axes' times at every sample alone took about 15 s per
    // axis on a desktop JVM, more on the phone.
    @Test
    @Timeout(value = 20, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    fun `a half-hour drive with the RaceBox and the phone accelerometer is read in seconds`() {
        val session = telemetrySessionOf(drive(), null)
        assertNotNull(session.channel(Aliases.LONGITUDINAL_ACCELERATION))
    }
}
