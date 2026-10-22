@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.analysis

import com.flappedear.betterdriver.analysis.confidence.SessionCodes
import com.flappedear.betterdriver.analysis.quality.Verdict
import com.flappedear.betterdriver.analysis.quality.sessionQuality
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.timing.geo.Xy
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A session on the phone's own GPS (FBBD-275), as PhoneGnssSource records it: one position a
 * second, a few metres off, with Android's accuracy estimate. Its laps are timed close to the truth,
 * every conclusion drawn from it is graded below High, and its GNSS values are never judged against
 * a receiver's criteria.
 */
class PhoneGpsSessionTest {
    private val sim = SimTrack.rectangle()

    /** The receiver channels as a phone's GPS, and its accuracy (app PhoneGnssChannels). */
    private val channels = GnssChannels.ALL.map { it.copy(provenance = Provenance.MEASURED_GPS) } +
        ChannelDef(710, "phone_gps_accuracy", "m", "phone", 1, Provenance.MEASURED_GPS)

    /** Android's typical accuracy estimate for a phone in a car under open sky. */
    private val accuracyMeters = 4.0

    /** [laps] laps every [stepSeconds], each position moved up to [noiseMeters] each way, as a phone stores it. */
    private fun session(laps: Int, stepSeconds: Double, noiseMeters: Double): SessionData {
        val random = Random(275)
        val sets = sim.samples(laps, stepSeconds).map { set ->
            val lat = set.values.first { it.channelId == GnssChannels.LAT.id }.value
            val lon = set.values.first { it.channelId == GnssChannels.LON.id }.value
            fun noise() = if (noiseMeters == 0.0) 0.0 else random.nextDouble(-noiseMeters, noiseMeters)
            val g = sim.geo(sim.frame.toXy(lat, lon) + Xy(noise(), noise()))
            val others = set.values.filter { it.channelId !in setOf(GnssChannels.LAT.id, GnssChannels.LON.id) }
            SampleSet(
                set.tMicros,
                listOf(
                    ChannelValue(GnssChannels.UTC_MS.id, (Start + set.tMicros / 1_000).toDouble()),
                    ChannelValue(GnssChannels.LAT.id, g.lat),
                    ChannelValue(GnssChannels.LON.id, g.lon),
                    ChannelValue(710, accuracyMeters),
                ) + others,
            )
        }
        val info = SessionInfo(Start, devices = listOf(Provenance.PhoneGnssDevice, "phone"), channels = channels)
        return SessionData(
            "phone", info, SessionState.FINISHED, channels.associateBy { it.id }, sets, emptyList(),
            Record.End(sets.last().tMicros, "user"), truncatedBytes = 0, skippedBytes = 0,
        )
    }

    private fun analyse(data: SessionData) = analyzeSession(telemetrySessionOf(data, sim.track()))

    @Test
    fun `laps on the phone's GPS are timed within a few tenths of the truth`() {
        val truth = analyse(session(5, stepSeconds = 0.04, noiseMeters = 0.0)).laps.timedLaps
        val phone = analyse(session(5, stepSeconds = 1.0, noiseMeters = 3.0)).laps.timedLaps
        assertEquals(truth.map { it.number }, phone.map { it.number })
        assertTrue(truth.size >= 4, "${truth.size} timed laps")
        for ((t, p) in truth.zip(phone)) {
            // A line crossing placed between positions a second apart, each a few metres off.
            assertEquals(t.durationSeconds, p.durationSeconds, 0.4, "lap ${t.number}")
        }
    }

    @Test
    fun `nothing drawn from the phone's GPS is graded High`() {
        val analysis = analyse(session(5, stepSeconds = 1.0, noiseMeters = 3.0))
        val session = analysis.confidence.session
        // Android's accuracy of 4 m leaves the whole session, so every lap in every segment, below High.
        assertTrue(session.level != Confidence.HIGH, session.why)
        assertTrue(session.has(SessionCodes.ACCURACY), session.why)
        for ((id, segment) in analysis.confidence.segments) {
            segment.laps.indices.forEach { assertTrue(analysis.confidence.lap(id, it).level != Confidence.HIGH) }
        }
        // The positions are a GPS's measurement, so not graded down as made up.
        assertEquals(Provenance.MEASURED_GPS, analysis.provenance.position)
    }

    @Test
    fun `its GNSS values are shown, never judged as a receiver's`() {
        val report = sessionQuality(session(3, stepSeconds = 1.0, noiseMeters = 3.0), sim.track())
        val gnss = report.sections.single { it.title == "GNSS" }
        val judged = gnss.metrics.filter { it.verdict == Verdict.PASS || it.verdict == Verdict.FAIL }
        assertTrue(judged.isEmpty(), "judged: ${judged.map { it.id }}")
        val rate = gnss.metrics.single { it.id == "gnss.rate" }
        assertEquals(Verdict.INFO, rate.verdict)
        assertEquals(1.0, rate.value!!, 0.05)
        assertTrue(rate.how.startsWith("phone GPS"), rate.how)
        // The soak takes nothing from them either.
        val soak = report.sections.flatMap { it.metrics }.filter { it.id.startsWith("soak.gnss") }
        assertTrue(soak.none { it.verdict == Verdict.FAIL }, soak.toString())
    }

    private companion object {
        const val Start = 1_790_000_000_000L
    }
}
