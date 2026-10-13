@file:Suppress("MagicNumber", "ReturnCount") // Variants, seeds and tolerances; early outs.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.SimTrack
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.focus.FocusMinimumLossSeconds
import com.flappedear.betterdriver.analysis.focus.sessionFocus
import com.flappedear.betterdriver.analysis.progress.ProgressAxis
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.TrackSegmentType
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Segmentation robustness (FBBD-215): the corners and straights the analysis finds, their CornerIds,
 * the theoretical best and the primary focus must not depend on how a recording happens to be
 * represented. Every fixture (the made-up circuits and Arek's real recordings) is analysed as
 * recorded and again after each of: float32 positions and speed (as FlappedEar Telemetry stores
 * them), small seeded GNSS noise, resampling to 25, 20 and 10 Hz, and isolated missing samples.
 *
 * Each variant must find the same corners and straights under the same ids, every one starting
 * within [MaxBoundaryMeters] of where it did, the theoretical best within
 * [MaxTheoreticalBestSeconds], and the same primary focus and largest time available. Both fresh
 * (a track's first session, which makes its corner identity) and with the original's identity kept
 * (every later session there), which must not make a new revision. Two focus areas, or two segments'
 * time available, within [TieSeconds] of each other are a tie: either may come first.
 */
class SegmentationRobustnessTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    private class Fixture(val name: String, val data: SessionData, val track: Track)

    private class Variant(val name: String, val apply: (SessionData) -> SessionData)

    private fun sims() = listOf(
        "rectangle" to SimTrack.rectangle(),
        "chicane" to SimTrack.chicane(),
        "hairpin" to SimTrack.hairpin(),
    ).map { (name, sim) -> Fixture(name, sim.session(laps = 5), sim.track()) }

    private fun real(): List<Fixture> {
        val silesia = RealCorpus.silesiaTrack()
        val made = RealCorpus.import(store, File(tmp, "staging"), RealCorpus.SILESIA, listOf(silesia))
        val road = RealCorpus.BETTERDRIVER.map { name ->
            val track = assertNotNull(RealCorpus.roadTrack(name), name)
            Fixture(name, store.read(RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id), track)
        }
        return listOf(Fixture(RealCorpus.SILESIA, store.read(made.id), silesia)) + road
    }

    private val variants = listOf(
        Variant("float32", ::float32),
        Variant("GNSS noise", { noisy(it, Random(NoiseSeed)) }),
        Variant("25 Hz", { resampled(it, 25.0) }),
        Variant("20 Hz", { resampled(it, 20.0) }),
        Variant("10 Hz", { resampled(it, 10.0) }),
        Variant("missing samples", { missing(it, Random(MissingSeed)) }),
    )

    /** What [variant] of [fixture] changed against [original], beyond tolerance. */
    private fun compare(fixture: Fixture, variant: Variant, original: SessionAnalysis): List<String> {
        val session = telemetrySessionOf(variant.apply(fixture.data), fixture.track)
        val fresh = analyzeSession(session)
        val kept = analyzeSession(session, identity = original.identity)
        val what = "${fixture.name} / ${variant.name}"
        val revised = "$what: made revision ${kept.identity?.number} of the original's identity"
        return differences("$what, fresh", original, fresh) +
            differences("$what, with the original's identity", original, kept) +
            listOfNotNull(revised.takeIf { kept.identity !== original.identity })
    }

    private fun differences(what: String, a: SessionAnalysis, b: SessionAnalysis): List<String> {
        val sa = a.segments?.segments.orEmpty()
        val sb = b.segments?.segments.orEmpty()
        if (sa.map { it.id to it.type } != sb.map { it.id to it.type }) {
            fun corners(s: List<com.flappedear.betterdriver.analysis.segments.TrackSegment>) =
                s.filter { it.type == TrackSegmentType.Corner }.map { "${it.id} ${it.name}" }
            return listOf("$what: ${sa.size} segments became ${sb.size}, corners ${corners(sa)} -> ${corners(sb)}")
        }
        val out = mutableListOf<String>()
        val axisA = assertNotNull(a.axis, what)
        val axisB = assertNotNull(b.axis, what)
        val moved = sa.zip(sb).map { (x, y) ->
            x to distance(axisA, x.startProgressMeters, axisB, y.startProgressMeters)
        }
        val (worst, meters) = moved.maxBy { it.second }
        if (meters > MaxBoundaryMeters) out += "$what: ${worst.name} starts ${fmt(meters)} m from where it did"
        val ta = a.theoreticalBest?.totalSeconds
        val tb = b.theoreticalBest?.totalSeconds
        val tbMoved = ta != null && tb != null && abs(ta - tb) > MaxTheoreticalBestSeconds
        if ((ta == null) != (tb == null) || tbMoved) {
            out += "$what: theoretical best $ta -> $tb s"
        }
        val focusA = focus(a)
        val focusB = focus(b)
        if (!sameFirst(focusA, focusB)) out += "$what: primary focus ${focusA.take(2)} -> ${focusB.take(2)}"
        val gainsA = gains(a)
        val gainsB = gains(b)
        val opportunity = (gainsA + gainsB).any { it.value >= FocusMinimumLossSeconds }
        if (opportunity && !sameFirst(gainsA, gainsB)) {
            out += "$what: largest time available ${gainsA.take(2)} -> ${gainsB.take(2)}"
        }
        return out
    }

    /** Something ranked: what it is about, and the value it is ranked by. */
    private data class Ranked(val key: String, val value: Double) {
        override fun toString() = "$key ${fmt3(value)}"
    }

    /** The session's focus areas in order: kind and segment, and the value they are ranked by. */
    private fun focus(a: SessionAnalysis) =
        sessionFocus(a).areas.map { Ranked("${it.area.kind} ${it.area.segmentId}", it.area.value) }

    /** Time available by segment, largest first. */
    private fun gains(a: SessionAnalysis) =
        a.summary?.gains.orEmpty().mapNotNull { row -> row.lossSeconds?.let { Ranked(row.segmentId, it) } }

    /**
     * Whether [b] puts first what [a] does, or something [a] ranks within [TieSeconds] of its first.
     */
    private fun sameFirst(a: List<Ranked>, b: List<Ranked>): Boolean {
        val first = a.firstOrNull() ?: return b.isEmpty()
        val other = b.firstOrNull() ?: return false
        return a.any { it.key == other.key && first.value - it.value <= TieSeconds }
    }

    private fun check(fixtures: List<Fixture>) {
        val problems = mutableListOf<String>()
        for (fixture in fixtures) {
            val original = analyzeSession(telemetrySessionOf(fixture.data, fixture.track))
            val corners = original.segments?.segments.orEmpty().count { it.type == TrackSegmentType.Corner }
            println(
                "${fixture.name}: ${original.laps.timedLaps.size} laps, $corners corners, theoretical best " +
                    "${original.theoreticalBest?.totalSeconds}, focus ${focus(original).take(2)}",
            )
            assertTrue(original.identified && corners >= 2, "${fixture.name}: corners to compare")
            if (!fixture.track.pointToPoint) {
                assertNotNull(original.theoreticalBest?.totalSeconds, "${fixture.name}: a theoretical best")
                assertTrue(focus(original).isNotEmpty(), "${fixture.name}: a focus to compare")
            }
            for (variant in variants) {
                val found = compare(fixture, variant, original)
                found.forEach(::println)
                problems += found
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
    }

    @Test
    fun `made-up circuits keep their corners, ids, theoretical best and focus under every variant`() =
        check(sims())

    @Test
    fun `Arek's recordings keep their corners, ids, theoretical best and focus under every variant`() =
        check(real())

    private companion object {
        /** The furthest a corner or straight's start may move (FBBD-215). */
        const val MaxBoundaryMeters = 5.0

        /** The most the theoretical best may change (FBBD-215). */
        const val MaxTheoreticalBestSeconds = 0.05

        /** Ranked values this close are a tie: no measurement tells them apart. */
        const val TieSeconds = 0.01

        const val NoiseSeed = 215
        const val MissingSeed = 172

        /** Position noise, metres (one sigma), and speed noise, km/h. */
        const val NoiseMeters = 0.3
        const val NoiseKmh = 0.2

        /** About one sample in this many is dropped, never two in a row. */
        const val MissingOneIn = 50

        /** Two samples further apart than this are not interpolated across. */
        const val MaxBridgeMicros = 1_000_000L

        const val MetersPerDegree = 111_195.0

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.1f", v)

        fun fmt3(v: Double) = String.format(Locale.ROOT, "%.3f", v)

        /** The point [meters] along [axis], in degrees. */
        fun pointOn(axis: ProgressAxis, meters: Double): GeoPoint {
            val c = axis.cumulative
            val i = c.indexOfLast { it <= meters }.coerceIn(0, c.size - 1)
            val j = minOf(i + 1, c.size - 1)
            val u = if (c[j] > c[i]) ((meters - c[i]) / (c[j] - c[i])).coerceIn(0.0, 1.0) else 0.0
            val p = axis.points[i]
            val q = axis.points[j]
            val frame = LocalFrame(GeoPoint(axis.origin.latitudeDegrees, axis.origin.longitudeDegrees))
            val east = p.eastMeters + (q.eastMeters - p.eastMeters) * u
            val north = p.northMeters + (q.northMeters - p.northMeters) * u
            return frame.toGeo(Xy(east, north))
        }

        fun distance(a: ProgressAxis, am: Double, b: ProgressAxis, bm: Double): Double {
            val pa = pointOn(a, am)
            val frame = LocalFrame(pa)
            return frame.toXy(pointOn(b, bm)).length()
        }

        fun mapValues(data: SessionData, f: (Int, ChannelValue) -> Double) = data.copy(
            sampleSets = data.sampleSets.mapIndexed { i, s ->
                SampleSet(s.tMicros, s.values.map { ChannelValue(it.channelId, f(i, it)) })
            },
        )

        val Kept = setOf(GnssChannels.LAT.id, GnssChannels.LON.id, GnssChannels.SPEED_KMH.id)

        /** Positions and speed stored as float32. */
        fun float32(data: SessionData) =
            mapValues(data) { _, v -> if (v.channelId in Kept) v.value.toFloat().toDouble() else v.value }

        /** Seeded Gaussian noise: [NoiseMeters] on each position axis, [NoiseKmh] on speed. */
        fun noisy(data: SessionData, random: Random): SessionData {
            fun gauss(): Double {
                // Box-Muller.
                val u = 1.0 - random.nextDouble()
                val v = random.nextDouble()
                return sqrt(-2.0 * ln(u)) * cos(2.0 * Math.PI * v)
            }
            return data.copy(
                sampleSets = data.sampleSets.map { s ->
                    val lat = s.values.firstOrNull { it.channelId == GnssChannels.LAT.id }?.value ?: 0.0
                    SampleSet(
                        s.tMicros,
                        s.values.map {
                            val noise = when (it.channelId) {
                                GnssChannels.LAT.id -> gauss() * NoiseMeters / MetersPerDegree
                                GnssChannels.LON.id ->
                                    gauss() * NoiseMeters / (MetersPerDegree * cos(Math.toRadians(lat)))
                                GnssChannels.SPEED_KMH.id -> gauss() * NoiseKmh
                                else -> 0.0
                            }
                            ChannelValue(it.channelId, it.value + noise)
                        },
                    )
                },
            )
        }

        /** Every [MissingOneIn]th sample or so dropped, at random, never two in a row nor the first or last. */
        fun missing(data: SessionData, random: Random): SessionData {
            val sets = data.sampleSets
            var dropped = false
            val kept = sets.filterIndexed { i, _ ->
                val drop = !dropped && i > 0 && i < sets.size - 1 && random.nextInt(MissingOneIn) == 0
                dropped = drop
                !drop
            }
            return data.copy(sampleSets = kept)
        }

        /**
         * The samples at [hz], each channel read straight between the two samples around the new time
         * that carry it (heading the short way round), none across a gap over [MaxBridgeMicros].
         */
        fun resampled(data: SessionData, hz: Double): SessionData {
            val sets = data.sampleSets.sortedBy { it.tMicros }
            val ids = sets.flatMap { s -> s.values.map { it.channelId } }.toSortedSet()
            val series = ids.associateWith { id ->
                val t = ArrayList<Long>()
                val v = ArrayList<Double>()
                for (s in sets) {
                    val value = s.values.firstOrNull { it.channelId == id }
                    if (value != null && (t.isEmpty() || s.tMicros > t.last())) {
                        t += s.tMicros
                        v += value.value
                    }
                }
                t.toLongArray() to v.toDoubleArray()
            }
            val first = sets.first().tMicros
            val last = sets.last().tMicros
            val step = 1e6 / hz
            val out = ArrayList<SampleSet>()
            var k = 0L
            while (true) {
                val t = first + (k++ * step).roundToLong()
                if (t > last) break
                val values = ids.mapNotNull { id ->
                    val (ts, vs) = series.getValue(id)
                    val position = id == GnssChannels.LAT.id || id == GnssChannels.LON.id
                    valueAt(ts, vs, t, id == GnssChannels.HEADING.id, position)?.let { ChannelValue(id, it) }
                }
                if (values.isNotEmpty()) out += SampleSet(t, values)
            }
            return data.copy(sampleSets = out)
        }

        fun valueAt(ts: LongArray, vs: DoubleArray, t: Long, circular: Boolean, position: Boolean): Double? {
            val at = ts.binarySearch(t)
            if (at >= 0) return vs[at]
            val next = -at - 1
            if (next == 0 || next >= ts.size) return null
            val prev = next - 1
            if (ts[next] - ts[prev] > MaxBridgeMicros) return null
            val a = vs[prev]
            val b = vs[next]
            // No fix (0°, 0°) on either side is no fix here.
            if (position && (a == 0.0 || b == 0.0)) return if (a == 0.0 && b == 0.0) 0.0 else null
            val u = (t - ts[prev]).toDouble() / (ts[next] - ts[prev])
            if (!circular) return a + (b - a) * u
            var d = b - a
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return (a + d * u + 360.0) % 360.0
        }
    }
}
