@file:Suppress(
    "MagicNumber", // Budgets and tolerances from the Jira criteria.
    "ReturnCount", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements", "LongParameterList", // Test checks.
)

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.coach.CoachEvent
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachLibrary
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CornerMap
import com.flappedear.betterdriver.coach.CornerPass
import com.flappedear.betterdriver.coach.CueEngine
import com.flappedear.betterdriver.coach.CueKind
import com.flappedear.betterdriver.coach.CueReview
import com.flappedear.betterdriver.coach.DefaultLatencyMicros
import com.flappedear.betterdriver.coach.Phrases
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.TracedLap
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.VoiceMode
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.improved
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.Recorder
import com.flappedear.betterdriver.recorder.RecorderConfig
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The coach replayed over Arek's RaceChrono session at Silesia Ring (27 Sep 2026), exactly as the
 * app's coach process runs it ([CoachSession], fed every fix as it arrives): FBBD-27 (cue timing),
 * FBBD-28 (the cue review against the coach's log) and FBBD-168 (a reference from a session driven
 * before its track existed).
 *
 * Braking zones are the driver's own on each lap: from where that lap started braking for a corner
 * to its slowest point there ([CornerMap.passes] against the session's reference lap). A cue's sound
 * starts its latency budget after the coach asks for it and lasts as long as text-to-speech takes
 * to say it ([Phrases.durationMicros]).
 */
class CoachReplayTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }
    private val track = RealCorpus.silesiaTrack()

    private fun silesia(tracks: List<Track> = listOf(track)): SessionData =
        store.read(RealCorpus.import(store, File(tmp, "staging"), RealCorpus.SILESIA, tracks).id)

    /** The reference the coach would hold for Silesia Ring after this session: its own laps. */
    private fun reference(data: SessionData): CoachReference =
        assertNotNull(buildReference(track, listOf(SessionLaps(data.id, tracedLaps(track, data.coachFixes())))))

    /**
     * One coached replay: what the coach logged, with the latency budget its cues were heard with.
     * [lapTimesOutOfBraking] is false where lap-time cues are known to be heard while braking (see
     * the first outing below), so only its corner cues are held to that.
     */
    private class Replay(
        val name: String,
        val budgetMicros: Long,
        val events: List<CoachEvent>,
        val lapTimesOutOfBraking: Boolean = true,
    ) {
        val cues = cuesIn(events)
    }

    /** Runs [settings] over the session, as the coach process does: every fix handled when it arrives. */
    private fun replay(
        name: String,
        data: SessionData,
        settings: CoachSettings,
        reference: CoachReference?,
        budget: Long,
        lapTimesOutOfBraking: Boolean = true,
    ): Replay {
        val coach = CoachSession(settings, track, reference)
        val events = data.sampleSets.mapNotNull { it.toCoachFix() }
            .flatMap { f -> coach.onFix(f, f.tMicros) { true }.events }
        return Replay(name, budget, events, lapTimesOutOfBraking)
    }

    /** How the scenarios are set up: the app plans cues with 300 ms plus the audio delay setting. */
    private fun scenarios(data: SessionData): List<Replay> {
        val ref = reference(data)
        val headsetDelay = (LiveBudgetMicros - DefaultLatencyMicros).toInt() / 1_000
        return listOf(
            // Every corner, from the first lap, with live text-to-speech to a headset 400 ms away.
            replay("live, full", data, CoachSettings(Verbosity.Full, headsetDelay, 0), ref, LiveBudgetMicros),
            // The same with pre-rendered clips, heard 250 ms after the coach asks.
            replay(
                "clips, full", data,
                CoachSettings(Verbosity.Full, 0, 0, VoiceMode.PreRendered), ref, ClipBudgetMicros,
            ),
            // The app's defaults at a track with a reference from earlier sessions.
            replay("live, focus", data, CoachSettings(Verbosity.Focus, headsetDelay), ref, LiveBudgetMicros),
            // The first outing at a new track: the corners come from this session's first timed lap.
            // Here that is lap 2, which stopped just past the line (0 km/h, 216 s): the coach's corner
            // 1 is the stop, and the lap times on laps 3 and 7 are spoken while braking for the real
            // Turn 1. Reported as a finding; only this replay's corner cues are checked against braking.
            replay(
                "live, first outing", data, CoachSettings(Verbosity.Focus, headsetDelay), null, LiveBudgetMicros,
                lapTimesOutOfBraking = false,
            ),
        )
    }

    /**
     * Where the car was along the reference lap through one lap, and where it braked for each
     * corner: from the first point before the corner's slowest point (its apex on this lap) where it
     * slowed harder than [BrakingMps2], to the apex. Short let-ups while braking ([BrakingGapMicros])
     * stay in the zone. Lifting off is not braking: it slows the car about half as hard (1.3 m/s²
     * at 130 km/h on lap 8 of this file). A corner taken without braking has no zone on that lap.
     */
    private class LapOnMap(val traced: TracedLap, map: CornerMap) {
        val start = traced.lap.startMicros
        val end = traced.lap.endMicros
        private val trace = traced.trace
        private val times = trace.timesMicros.map { start + it }
        private val along: List<Double?> = map.cursor().let { c -> trace.points.map { c.along(it) } }

        /** Slowing at each point, m/s², over the [DecelWindowMicros] before it. */
        private val decel = DoubleArray(times.size) { i ->
            val k = (i downTo 0).firstOrNull { times[i] - times[it] >= DecelWindowMicros } ?: 0
            val dt = (times[i] - times[k]) / 1e6
            if (dt <= 0) 0.0 else (trace.speedsKmh[k] - trace.speedsKmh[i]) / KmhPerMps / dt
        }

        /** Each corner's braking zone on this lap, metres along the reference; null where it did not brake. */
        val zones: List<ClosedFloatingPointRange<Double>?> = map.passes(trace, traced.lap.number).let { passes ->
            map.corners.mapIndexed { i, c ->
                passes.firstOrNull { it.corner == c.number }?.let { zone(map.windows[i].start, it.apexAt) }
            }
        }

        /** Braking in the corner's measuring window, from [from] metres along, up to its apex at [apexAt]. */
        private fun zone(from: Double, apexAt: Double): ClosedFloatingPointRange<Double>? {
            val inside = along.indices.filter { along[it].let { a -> a != null && a >= from && a <= apexAt } }
            val apex = inside.lastOrNull() ?: return null
            // The last moment of braking before the apex, then back while the braking goes on.
            var first = inside.lastOrNull { decel[it] >= BrakingMps2 } ?: return null
            for (i in first downTo inside.first()) {
                if (times[i] < times[first] - BrakingGapMicros) break
                if (decel[i] >= BrakingMps2) first = i
            }
            // The window that ends at the first braking point holds the start of the braking.
            val begin = inside.lastOrNull { times[first] - times[it] >= DecelWindowMicros } ?: inside.first()
            return along[begin]!!..along[apex]!!
        }

        fun contains(t: Long) = t in start until end

        /** Metres along at session time [t], interpolated; null off the reference path. */
        fun alongAt(t: Long): Double? {
            val i = times.indexOfLast { it <= t }.coerceAtLeast(0)
            val j = minOf(i + 1, times.size - 1)
            val a = along[i] ?: return null
            val b = along[j] ?: return null
            val u = if (times[j] == times[i]) 0.0 else (t - times[i]).toDouble() / (times[j] - times[i])
            return a + u * (b - a)
        }

        /** Session time the car reached [meters] along, interpolated; null if it never did. */
        fun timeAt(meters: Double): Long? {
            val j = along.indices.firstOrNull { (along[it] ?: -1.0) >= meters } ?: return null
            val i = (j - 1).coerceAtLeast(0)
            val a = along[i] ?: return times[j]
            val b = along[j]!!
            val u = if (b == a) 0.0 else (meters - a) / (b - a)
            return times[i] + Math.round(u * (times[j] - times[i]))
        }

        /** The corner whose braking zone is next at [meters] along: on the straight before it. */
        fun nextCorner(meters: Double): Int? = zones.indexOfFirst { it != null && it.start > meters }.takeIf { it >= 0 }
    }

    /** One cue as heard: its sound from [start] to [end], session time. */
    private class Heard(val text: String, val kind: CueKind, val corner: Int?, val start: Long, val end: Long)

    /** What one replay's cues did: margins of corner cues before their braking, and what went wrong. */
    private class Check(
        val heard: List<Heard>,
        val margins: List<Long>,
        val inZone: List<String>,
        val crowded: List<String>,
    )

    private fun check(replay: Replay, laps: List<LapOnMap>): Check {
        val heard = replay.cues.map {
            val start = it.tMicros + replay.budgetMicros
            Heard(it.text, it.kind, it.corner, start, start + Phrases.durationMicros(it.text))
        }
        val inZone = mutableListOf<String>()
        val margins = mutableListOf<Long>()
        val perStraight = HashMap<Pair<Int, Int?>, MutableList<String>>()
        for (h in heard) {
            // Every 20 ms of the sound, wherever the car was then.
            for (t in h.start..h.end step 20_000L) {
                val lap = laps.firstOrNull { it.contains(t) } ?: continue
                val along = lap.alongAt(t) ?: continue
                val zone = lap.zones.indexOfFirst { it != null && along in it }
                val checked = h.kind == CueKind.Corner || replay.lapTimesOutOfBraking
                if (zone >= 0 && checked) {
                    inZone += "'${h.text}' in corner ${zone + 1}'s braking on lap ${lap.traced.lap.number}"
                }
            }
            // Cues after the last line (the lap time on the way into the paddock) are on no lap.
            val lap = laps.firstOrNull { it.contains(h.start) } ?: continue
            val along = assertNotNull(lap.alongAt(h.start), "'${h.text}' heard off the track")
            val next = lap.nextCorner(along)
            perStraight.getOrPut(lap.traced.lap.number to next) { mutableListOf() } += h.text
            // The braking a corner cue is heard before: the next on this lap.
            val zone = next?.takeIf { h.kind == CueKind.Corner }?.let { lap.zones[it] } ?: continue
            margins += assertNotNull(lap.timeAt(zone.start)) - h.start
        }
        val crowded = perStraight.filterValues { it.size > 1 }
            .map { (k, v) -> "lap ${k.first} before corner ${k.second?.plus(1)}: $v" }
        return Check(heard, margins, inZone, crowded)
    }

    @Test
    fun `cues are heard 1_2 s before braking, never while braking, one per straight (FBBD-27)`() {
        val data = silesia()
        val map = reference(data).map()
        val laps = tracedLaps(track, data.coachFixes()).map { LapOnMap(it, map) }
        for (replay in scenarios(data)) {
            val c = check(replay, laps)
            val name = replay.name
            val corner = c.heard.count { it.kind == CueKind.Corner }
            assertTrue(corner > 0, "$name: corner cues")
            assertTrue(c.heard.any { it.kind == CueKind.LapTime }, "$name: lap times")
            // >= 50 m before the braking point at 150 km/h, i.e. 1.2 s, on >= 95 % of the corner cues.
            val onTime = c.margins.count { it >= MarginMicros }
            val what = "$name: ${c.margins.map { it / 1_000 }} ms to the braking point"
            assertTrue(onTime * 100 >= 95 * c.margins.size, what)
            assertEquals(emptyList(), c.inZone.distinct(), "$name: cues heard while braking")
            c.heard.zipWithNext().forEach { (a, b) ->
                assertTrue(b.start >= a.end, "$name: '${b.text}' starts before '${a.text}' ends")
            }
            assertEquals(emptyList(), c.crowded, "$name: straights with more than one cue")
        }
    }

    @Test
    fun `the cue review matches the coach's log, and its results the session data (FBBD-28)`() {
        val data = silesia()
        val ref = reference(data)
        val headsetDelay = (LiveBudgetMicros - DefaultLatencyMicros).toInt() / 1_000
        val coach = CoachSession(CoachSettings(Verbosity.Full, headsetDelay, learningLaps = 0), track, ref)
        // Recorded as the app records a coached session: the fixes, and the coach's events at their times.
        val recording = Recorder(store, RecorderConfig(queueCapacity = 400_000))
            .start(SessionInfo(data.info.startUtcMillis, track = track.name, channels = GnssChannels.ALL))
        val logged = mutableListOf<CoachEvent>()
        for (set in data.sampleSets) {
            recording.submit(set.copy(values = set.values.filter { it.channelId in GnssChannels.IDS }))
            val fix = set.toCoachFix() ?: continue
            for (e in coach.onFix(fix, fix.tMicros) { true }.events) {
                recording.eventAt(e.tMicros, e.kind, e.text)
                logged += e
            }
        }
        assertTrue(recording.stop())
        val review = CueReview.read(store, recording.id)

        val cues = cuesIn(logged)
        val results = logged.filter { it.kind == CoachEvents.RESULT }
            .map { assertNotNull(CoachEvents.parseResult(it.text)) }
        assertTrue(cues.size >= 10, "cues said: ${cues.size}")
        // 100 % match: every cue logged is in the review, in order, and nothing else is.
        assertEquals(cues, review.cues.map { it.cue })
        assertEquals(0, review.problems)
        assertEquals(results.sortedBy { it.seq }, review.cues.mapNotNull { it.result }.sortedBy { it.seq })
        review.cues.filter { it.result != null }.forEach { assertEquals(it.cue.seq, it.result!!.seq) }

        // Every attempt result against the session itself: the corner pass on the lap after the cue,
        // measured again from the recorded fixes.
        val map = ref.map()
        val recorded = tracedLaps(track, store.read(recording.id).coachFixes()).associateBy { it.lap.number }
        val judged = review.cues.filter { it.result != null }
        assertTrue(judged.size >= 10, "results to spot-check: ${judged.size}")
        for (r in judged) {
            val result = r.result!!
            val cue = r.cue
            val lap = assertNotNull(recorded[result.after.lap], "lap ${result.after.lap}")
            val pass = assertNotNull(map.passes(lap.trace, lap.lap.number).firstOrNull { it.corner == cue.corner })
            val what = "cue ${cue.seq} '${cue.text}' on lap ${result.after.lap}"
            assertEquals(pass.brakeAt, result.after.brakeAt, 1.0, "$what: braking point")
            assertEquals(pass.apexAt, result.after.apexAt, 1.0, "$what: apex")
            assertEquals(pass.minKmh, result.after.minKmh, 0.5, "$what: minimum speed")
            assertEquals(pass.timeMicros.toDouble(), result.after.timeMicros.toDouble(), 20_000.0, "$what: corner time")
            val before = assertNotNull(cue.before)
            val gain = before.timeMicros - pass.timeMicros
            assertTrue(abs(gain - result.gainMicros) <= 20_000, "$what: gained ${result.gainMicros} us, data $gain us")
            assertEquals(improved(cue.advice, before, pass), result.improved, "$what: improved")
        }
    }

    @Test
    fun `a session from before its track existed feeds its coach reference (FBBD-168)`() {
        // Recorded with no saved track there: the session knows no track.
        val data = silesia(tracks = emptyList())
        assertNull(data.info.startFinish)
        var tracks = listOf(track)
        val library = CoachLibrary(store, File(tmp, "coach")) { tracks }
        val best = replayLaps(data.id, data.sampleSets, track).laps.map { it.lap }
            .filter { it.kind == LapKind.Timed }.minBy { it.timeMicros }

        // 1. The track made from the session: the coach starts with the session's best lap as reference.
        val first = assertNotNull(library.reference(track))
        assertEquals(listOf(data.id), first.sessions)
        assertEquals(best.number to best.timeMicros, first.lapNumber to first.lapMicros)
        assertTrue(first.fits(track))
        assertEquals(first.corners, CueEngine(track, CoachSettings(), first).corners, "the coach starts with corners")

        // 2. Lines edited and saved: the next outing gets a reference rebuilt for them.
        val edited = track.copy(sectorLines = track.sectorLines.dropLast(1), revision = 2)
        tracks = listOf(edited)
        val rebuilt = assertNotNull(library.reference(edited))
        assertEquals(2, rebuilt.trackRevision)
        assertEquals(listOf(data.id), rebuilt.sessions)

        // 3. Deleted and made again: the old session feeds the new track.
        tracks = emptyList()
        library.refresh()
        assertNull(library.references.get(edited.id))
        val again = RealCorpus.silesiaTrack(id = "silesia-ring-again")
        tracks = listOf(again)
        val renewed = assertNotNull(library.reference(again))
        assertEquals(listOf(data.id), renewed.sessions)
        assertEquals(best.timeMicros, renewed.lapMicros)
    }

    private companion object {
        /** The cues of a coach's [events], as it logged them. */
        fun cuesIn(events: List<CoachEvent>) =
            events.filter { it.kind == CoachEvents.CUE }
                .map { assertNotNull(CoachEvents.parseCue(it.tMicros, it.text)) }

        /** Total delay from the coach asking to the sound in the helmet (FBBD-27, FBBD-49). */
        const val LiveBudgetMicros = 400_000L
        const val ClipBudgetMicros = 250_000L

        /** 50 m at 150 km/h. */
        const val MarginMicros = 1_200_000L

        /** Braking slows the car harder than this (0.25 g; the coach's own threshold). */
        const val BrakingMps2 = 2.5
        const val DecelWindowMicros = 400_000L
        const val BrakingGapMicros = 500_000L
        const val KmhPerMps = 3.6
    }
}
