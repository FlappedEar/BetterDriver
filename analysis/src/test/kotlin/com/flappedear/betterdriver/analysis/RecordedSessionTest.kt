package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.compare.compareLaps
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecordedSessionTest {
    private val centerLat = 50.0
    private val centerLon = 19.0
    private val metersPerDegreeLat = 111_195.0
    private val metersPerDegreeLon = metersPerDegreeLat * cos(centerLat * PI / 180.0)

    /** Laps of a 200 m radius circle at 25 Hz, anticlockwise from the south; lap k is a bit slower. */
    private fun circleSession(laps: Int): SessionData {
        val radius = 200.0
        val sets = mutableListOf<SampleSet>()
        var t = 0L
        val stepMicros = 40_000L
        for (lap in 0 until laps) {
            val speed = 30.0 - lap // m/s
            val steps = (2 * PI * radius / speed / 0.04).toInt()
            for (i in 0 until steps) {
                val angle = -PI / 2 + 2 * PI * i / steps
                val east = radius * cos(angle)
                val north = radius * sin(angle)
                sets += SampleSet(
                    t,
                    listOf(
                        ChannelValue(GnssChannels.LAT.id, centerLat + north / metersPerDegreeLat),
                        ChannelValue(GnssChannels.LON.id, centerLon + east / metersPerDegreeLon),
                        ChannelValue(GnssChannels.SPEED_KMH.id, speed * 3.6),
                    ),
                )
                t += stepMicros
            }
        }
        // Out of order on disk, as the store may return them, plus a no-fix sample.
        sets.add(0, sets.removeAt(5))
        val noFix = listOf(ChannelValue(GnssChannels.LAT.id, 0.0), ChannelValue(GnssChannels.LON.id, 0.0))
        sets += SampleSet(t, noFix)
        val info = SessionInfo(startUtcMillis = 0, channels = GnssChannels.ALL)
        return SessionData(
            "s", info, SessionState.FINISHED, GnssChannels.ALL.associateBy { it.id }, sets,
            emptyList(), null, 0, 0,
        )
    }

    /** A 30 m line across the circle's southmost point, for anticlockwise travel (east). */
    private val line = TrackLine(
        left = GeoPoint(centerLat + (-200.0 + 15) / metersPerDegreeLat, centerLon),
        right = GeoPoint(centerLat + (-200.0 - 15) / metersPerDegreeLat, centerLon),
    )

    @Test
    fun `channels are in time order with aliases and the start line as gate`() {
        val session = telemetrySessionOf(circleSession(2), line)
        val lat = assertNotNull(session.channel(Aliases.LATITUDE))
        assertTrue(lat.timestamps.asList().zipWithNext().all { (a, b) -> b > a })
        assertEquals(0.0, lat.timestamps.first())
        assertTrue(lat.values.last().isNaN(), "0,0 is no fix")
        assertNotNull(session.channel(Aliases.SPEED))
        assertEquals(1, session.timingGates.size)
    }

    @Test
    fun `a recorded session is analysed and two laps compared`() {
        val session = telemetrySessionOf(circleSession(5), line)
        val analysis = analyzeSession(session)
        // Passes 2 to 5 bound three full laps; the first lap starts at the line without crossing it.
        assertTrue(analysis.laps.timedLaps.size >= 3, "laps: ${analysis.laps.timedLaps.size}")
        val fastest = assertNotNull(analysis.laps.fastestLapIndex)
        val slowest = analysis.laps.timedLaps.indices.maxBy { analysis.laps.timedLaps[it].durationSeconds }
        val comparison = assertNotNull(compareLaps(analysis, session, fastest, slowest))
        val last = comparison.delta.last().last()
        val timed = analysis.laps.timedLaps
        val expected = timed[slowest].durationSeconds - timed[fastest].durationSeconds
        assertTrue(expected > 0, "laps ${timed.map { it.durationSeconds }}")
        assertTrue(abs(last.value - expected) < 0.05, "delta at the line ${last.value} vs $expected")
        // Speed is constant within a lap; the ends may read the next lap's samples.
        val speeds = comparison.referenceSpeed.flatten().map { it.value }
        val median = speeds.sorted()[speeds.size / 2]
        assertTrue(speeds.count { abs(it - median) > 1e-3 } < speeds.size / 50, "speeds $speeds")
    }
}
