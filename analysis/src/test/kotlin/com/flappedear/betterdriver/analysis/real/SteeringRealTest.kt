@file:Suppress("MagicNumber") // Arek's laps, the FR-S's steering and the thresholds the tests check.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.data.Aliases
import com.flappedear.betterdriver.analysis.data.Interpolation
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.geo.unprojectCoordinate
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.Provenance
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.steering.SteerAdvice
import com.flappedear.betterdriver.timing.steering.bends
import com.flappedear.betterdriver.timing.steering.wheelForBend
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Wheel added at a time of a session, degrees. */
private typealias Edit = (TelemetrySession, Double) -> Double

/**
 * Steering advice (FBBD-321) through the line analysis on Arek's six Jastrząb sessions of 29 Aug 2026.
 * There are no FR-S track laps yet, and these were driven in another car without a steering channel,
 * so each session gets the wheel an FR-S would need for the path it drove: the bend of the measured
 * GNSS path through the FR-S's ratio and wheelbase, plus a fixed understeer of [UndersteerDegPerG].
 * That makes the push reading circular (the car model is the one the analysis assumes); what this
 * checks is the rest: every corner pass is read, a car that behaves the same on every lap gets no
 * push or correction advice from GNSS noise, and one pass where the front slid is found and named.
 * The first FR-S track session replaces this with the real wheel.
 */
class SteeringRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private class Day(val own: TelemetrySession, val others: List<LineSession>)

    private fun day(edit: Edit? = null): Day {
        val store = SessionStore(File(tmp, "sessions-" + System.nanoTime()))
        val sessions = RealCorpus.JASTRZAB_DAY.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            name.substringAfter("2026-08-29-").take(4) to telemetrySessionOf(store.read(id), track)
        }
        val own = withSteering(sessions.single { it.first == "1606" }.second, edit)
        val others = sessions.filter { it.first != "1606" }.map { (time, s) ->
            val steered = withSteering(s, null)
            LineSession(steered, deriveSourceLapSession(steered), time, "s$time")
        }
        return Day(own, others)
    }

    private fun line(day: Day): LineAnalysis =
        assertNotNull(analyzeLine(analyzeSession(day.own), day.own, day.others))

    @Test
    fun `every corner pass reads the hands - a car the same every lap gets no push or smoothing advice`() {
        val line = line(day())
        val read = line.corners.filter { it.reference.steering != null && it.target.steering != null }
        // Every corner of this track bends enough to need wheel; a kink below MinCornerWheelDeg may not.
        assertTrue(read.size >= line.corners.size - 1, "read ${read.size} of ${line.corners.size}")
        read.forEach { c ->
            val s = c.reference.steering!!
            assertTrue(s.peakDeg in 15.0..400.0, "${c.name} peak ${s.peakDeg}")
            assertTrue(s.turnInAt <= s.peakAt, c.name)
            assertTrue(s.pushDeg != null, c.name)
        }
        val advice = line.corners.flatMap { it.advice.steering }
        // The same understeer on every lap: no pass slid more than another beyond what GNSS bends repeat.
        assertTrue(SteerAdvice.Less !in advice, "advice $advice")
        // A wheel worked out from a GNSS path is noisier than a real one, so this also bounds the noise.
        assertTrue(SteerAdvice.Smoother !in advice, "advice $advice")
        assertTrue(SteerAdvice.Oversteer !in advice, "advice $advice")
    }

    @Test
    fun `the corner where the front slid on the fastest lap says less wheel, the others do not`() {
        val plain = line(day())
        val top = plain.ranked.first { it.reference.steering != null }
        val apex = top.reference.line.minByOrNull { abs(it.progressMeters - top.reference.steering!!.peakAt) }!!
        val analysis = analyzeSession(day().own)
        val origin = assertNotNull(analysis.axis).origin
        val at = unprojectCoordinate(apex.eastMeters, apex.northMeters, origin)
        val lap = analysis.laps.timedLaps.single { it.number == plain.referenceLapNumber }
        val frame = LocalFrame(GeoPoint(at.latitudeDegrees, at.longitudeDegrees))
        val side = if (top.turnsLeft) 1.0 else -1.0
        // On that lap only, 30° more wheel within 25 m of the peak: the path is unchanged, so the front slid.
        val pushed = line(
            day { s, t ->
                if (t < lap.startTelemetryTime || t > lap.endTelemetryTime) return@day 0.0
                val lat = s.valueAt(Aliases.LATITUDE, t) ?: return@day 0.0
                val lon = s.valueAt(Aliases.LONGITUDE, t, Interpolation.Longitude) ?: return@day 0.0
                if (frame.toXy(lat, lon).length() <= 25.0) side * 30.0 else 0.0
            },
        )
        val corner = pushed.corners.single { it.segmentId == top.segmentId }
        assertTrue(SteerAdvice.Less in corner.advice.steering, "${corner.name} ${corner.advice.steering}")
        pushed.corners.filter { it.segmentId != top.segmentId }.forEach {
            assertTrue(SteerAdvice.Less !in it.advice.steering, "${it.name} ${it.advice.steering}")
        }
    }

    companion object {
        /** About 1.1° of road wheel per g, a mild understeer; the same on every lap. */
        const val UndersteerDegPerG = 15.0

        /** The bend is read over 20 m: a GNSS path at 10 Hz bends more noisily than a car's wheel turns. */
        const val BendHalfMeters = 10.0

        /**
         * [session] with "obd_steering_angle" (degrees, positive left, as the FR-S's CAN gives it) at
         * every GNSS fix: the wheel for the bend of the driven path and the lateral g it takes at that
         * speed, plus [edit] at each time.
         */
        fun withSteering(session: TelemetrySession, edit: Edit?): TelemetrySession {
            val lat = assertNotNull(session.channel(Aliases.LATITUDE))
            val times = lat.timestamps.filter {
                session.valueAt(Aliases.LATITUDE, it) != null &&
                    session.valueAt(Aliases.LONGITUDE, it, Interpolation.Longitude) != null
            }
            val first = times.first()
            val frame = LocalFrame(
                GeoPoint(session.valueAt(Aliases.LATITUDE, first)!!, session.valueAt(Aliases.LONGITUDE, first)!!),
            )
            val points = times.map {
                frame.toXy(session.valueAt(Aliases.LATITUDE, it)!!, session.valueAt(Aliases.LONGITUDE, it)!!)
            }
            val along = DoubleArray(points.size)
            for (i in 1 until points.size) along[i] = along[i - 1] + (points[i] - points[i - 1]).length()
            val bend = bends(points, along, BendHalfMeters)
            val wheel = DoubleArray(times.size) { i ->
                val b = bend[i] ?: 0.0
                val mps = (session.valueAt(Aliases.SPEED, times[i]) ?: 0.0) / 3.6
                val g = mps * mps * b / 9.80665
                wheelForBend(b) + UndersteerDegPerG * g + (edit?.invoke(session, times[i]) ?: 0.0)
            }
            val channel = TelemetryChannel(
                "obd_steering_angle", times.toDoubleArray(), wheel, "°", Provenance.MEASURED_CAN,
            )
            return TelemetrySession(
                session.channels + (channel.name to channel), session.aliases, session.timingGates, session.metadata,
            )
        }
    }
}
