@file:Suppress("MagicNumber") // Made-up track geometry.

package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deterministic stand-in for a GNSS receiver: fixes around a circular "track", so tests and the
 * alpha build can record without hardware. Fix n is always the same, which lets tests check that a
 * recovered session holds exactly the fixes that were sent.
 */
class FakeGnss(
    val rateHz: Int = 25,
    val centerLat: Double = 52.0,
    val centerLon: Double = 21.0,
    val radiusM: Double = 200.0,
    val lapSeconds: Double = 40.0,
    val startUtcMillis: Long = 1_760_169_600_000L, // 2025-10-11T08:00:00Z
) {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(centerLat))

    /** A start/finish line across the track at angle 0 (due east of the centre). */
    val startFinish: Gate
        get() {
            val inner = radiusM - 15
            val outer = radiusM + 15
            return Gate(
                "Start / Finish",
                centerLat, centerLon + inner / mPerDegLon,
                centerLat, centerLon + outer / mPerDegLon,
            )
        }

    fun utcMillis(n: Long): Long = startUtcMillis + n * 1_000 / rateHz

    fun fix(n: Long): List<ChannelValue> {
        val t = n.toDouble() / rateHz
        val angle = 2 * PI * t / lapSeconds
        val lat = centerLat + radiusM * sin(angle) / mPerDegLat
        val lon = centerLon + radiusM * cos(angle) / mPerDegLon
        val speedKmh = 2 * PI * radiusM / lapSeconds * 3.6
        // Counter-clockwise travel: heading is the tangent, measured clockwise from north.
        val heading = ((Math.toDegrees(-angle) % 360.0) + 360.0) % 360.0
        return listOf(
            ChannelValue(GnssChannels.UTC_MS.id, utcMillis(n).toDouble()),
            ChannelValue(GnssChannels.LAT.id, lat),
            ChannelValue(GnssChannels.LON.id, lon),
            ChannelValue(GnssChannels.SPEED_KMH.id, speedKmh),
            ChannelValue(GnssChannels.HEADING.id, heading),
            ChannelValue(GnssChannels.ALT_M.id, 100.0 + 2 * sin(angle)),
            ChannelValue(GnssChannels.SATS.id, 14.0),
        )
    }
}

/** Pushes [FakeGnss] fixes into the sink in real time on its own thread. */
class FakeGnssSource(
    private val gnss: FakeGnss = FakeGnss(),
    /** Called after each fix is handed to the sink, with its index. Used by the fault-injection test. */
    private val onEmitted: (Long) -> Unit = {},
) : DataSource {
    override val name = "fake-gnss"

    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun start(sink: SampleSink) {
        GnssChannels.ALL.forEach(sink::defineChannel)
        running = true
        thread = Thread({
            val periodNanos = 1_000_000_000L / gnss.rateHz
            val t0 = System.nanoTime()
            var n = 0L
            while (running) {
                val due = t0 + n * periodNanos
                val wait = due - System.nanoTime()
                if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                if (!running) break
                sink.submit(SampleSet(sink.nowMicros(), gnss.fix(n)))
                onEmitted(n)
                n++
            }
        }, "fake-gnss").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(1_000)
    }
}
