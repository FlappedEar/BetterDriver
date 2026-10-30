// Cuts and tolerances; early outs; one check after another, each reported, in a test that walks every screen.
@file:Suppress(
    "MagicNumber", "ReturnCount", "TooManyFunctions", "CyclomaticComplexMethod", "NestedBlockDepth",
    "LoopWithTooManyJumpStatements",
)

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.identity.analyzeKeepingIdentity
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeDay
import com.flappedear.betterdriver.analysis.map.GgTrace
import com.flappedear.betterdriver.analysis.compare.ChartPoint
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.analysis.review.reviewSession
import com.flappedear.betterdriver.analysis.summary.EarlierSession
import com.flappedear.betterdriver.analysis.summary.PreviousLaps
import com.flappedear.betterdriver.feature.analysis.AnalysisUi
import com.flappedear.betterdriver.feature.analysis.trackDayUi
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every screen of Arek's recorded sessions at both tracks, end to end (FBBD-396): the six Autodrom
 * Jastrząb sessions of 29 Aug 2026 and the three Silesia Ring sessions of 27 Sep 2026, imported as the
 * app imports them, then read through [reviewSession], the one entry the iPhone, iPad and Android
 * review screens read, and on to each screen's model: the session list's laps, Summary (the three lap
 * times, what to work on, where time goes, progress against the sessions before), Compare and Map
 * for every lap against the fastest (with its G-G), Laps, Corners and every pass through each corner,
 * the Car tab's figures, the whole session's G-forces and runs, and the track day of each day.
 *
 * Each session is also read after each edge case a drive really has: the recording started or
 * stopped in the middle of a lap, a single lap, less than a lap, a long GPS loss, a long stop in the
 * middle, a 1 Hz receiver, repeated samples, no saved track, and nothing recorded at all. Whatever
 * the case, nothing may throw, and what the screens show must hold together:
 * - laps numbered in order, each a real time shorter than the session; one fastest, which is the
 *   shortest counted lap, at 0.000 against itself and every other counted lap behind it;
 * - raw theoretical best <= realistic optimal <= best lap, and the gains their differences;
 * - the screen's best lap and laps the same as the session list's;
 * - at most three focus areas, one of them primary;
 * - every chart point and G-G point a number, every G under [MaxG];
 * - no "NaN" or "Infinity" anywhere in a screen's text;
 * - without laps, the drive on the map with its distance and speeds;
 * - a cut or damaged recording never shows a lap faster than the whole recording's best.
 * All problems are reported together, each with its session and case.
 */
class ScreensRealDataTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    private class Recorded(val name: String, val data: SessionData, val track: Track)

    private class Case(val name: String, val apply: (SessionData) -> SessionData, val tracked: Boolean = true)

    private fun day(files: List<String>, track: Track): List<Recorded> = files.map { name ->
        val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
        Recorded(name.substringBefore("-racechrono"), store.read(id), track)
    }

    private val cases = listOf(
        Case("started mid-lap", { cut(it, 0.37, 1.0) }),
        Case("stopped mid-lap", { cut(it, 0.0, 0.71) }),
        Case("middle third", { cut(it, 0.33, 0.66) }),
        Case("first 4 min", { firstSeconds(it, 240.0) }),
        Case("first 60 s", { firstSeconds(it, 60.0) }),
        Case("GPS lost 30 s mid-session", { gpsLoss(it, 0.5, 30.0) }),
        Case("GPS lost 3 s every 2 min", { repeatedLoss(it, 3.0, 120.0) }),
        Case("stopped 3 min mid-session", { stopped(it, 0.2, 0.8, 180.0) }),
        Case("1 Hz", { everyNth(it, 10) }),
        Case("every sample twice", ::doubled),
        Case("no saved track", { it }, tracked = false),
        Case("nothing recorded", { it.copy(sampleSets = emptyList()) }),
        Case("one fix", { it.copy(sampleSets = it.sampleSets.take(1)) }),
    )

    private val problems = mutableListOf<String>()

    private fun problem(where: String, what: String) {
        problems += "$where: $what"
    }

    @Test
    fun `every screen of both tracks' sessions holds together, as recorded and in every edge case`() {
        val days = listOf(
            day(RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack()),
            day(RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack()),
        )
        for (sessions in days) {
            for ((i, s) in sessions.withIndex()) {
                val others = sessions.filter { it !== s }
                val reviewed = review(s.name, s.data, listOf(s.track), others) ?: continue
                val laps = reviewed.review.laps
                println(
                    "${s.name}: ${laps.size} laps, best ${reviewed.review.bestLapSeconds}, " +
                        "theoretical ${reviewed.review.theoreticalBestSeconds}",
                )
                if (laps.isEmpty()) problem(s.name, "no laps as recorded")
                screens(s.name, reviewed, durationSeconds(s.data), everyLap = true)
                progress(s.name, reviewed, sessions.take(i).reversed())
                val best = reviewed.review.bestLapSeconds
                for (case in cases) {
                    val where = "${s.name} / ${case.name}"
                    val data = try {
                        case.apply(s.data)
                    } catch (e: IllegalArgumentException) {
                        problem(where, "could not make the case: $e")
                        continue
                    }
                    val tracks = if (case.tracked) listOf(s.track) else emptyList()
                    val cut = review(where, data, tracks, others) ?: continue
                    screens(where, cut, durationSeconds(data), everyLap = false)
                    val cutBest = cut.review.bestLapSeconds
                    // A partial lap counted as a lap shows a lap faster than any driven.
                    val slack = if (case.name == "1 Hz") 0.6 else 0.15
                    if (best != null && cutBest != null && cutBest < best - slack) {
                        problem(where, "best lap ${fmt(cutBest)} s, faster than the whole session's ${fmt(best)} s")
                    }
                    if (cut.review.laps.size > laps.size + 1) {
                        problem(where, "${cut.review.laps.size} laps from a session of ${laps.size}")
                    }
                    if (!case.tracked && cut.drive == null && data.sampleSets.size > 1) {
                        problem(where, "no drive on the map without a track")
                    }
                }
            }
            trackDay(sessions)
        }
        if (problems.isNotEmpty()) fail("${problems.size} problems:\n" + problems.joinToString("\n"))
    }

    /** [reviewSession] as the review library calls it, the other sessions there setting the targets. */
    @Suppress("TooGenericExceptionCaught") // Any throw is a screen that would not open.
    private fun review(
        where: String,
        data: SessionData,
        tracks: List<Track>,
        others: List<Recorded>,
    ): ReviewedSession? =
        try {
            reviewSession(data, tracks) { track ->
                others.asSequence().map { o ->
                    val session = telemetrySessionOf(o.data, track)
                    LineSession(session, deriveSourceLapSession(session), o.name, o.data.id)
                }
            }
        } catch (e: Exception) {
            problem(where, "review threw $e")
            null
        }

    @Suppress("TooGenericExceptionCaught", "LongMethod") // One screen after another.
    private fun screens(where: String, reviewed: ReviewedSession, duration: Double?, everyLap: Boolean) {
        val review = reviewed.review
        val laps = review.laps
        // The session list and Laps tab.
        laps.zipWithNext().forEach { (a, b) ->
            if (b.number <= a.number) problem(where, "lap ${b.number} after lap ${a.number}")
        }
        laps.forEach {
            if (!it.seconds.isFinite() || it.seconds <= 0.0) problem(where, "lap ${it.number} lasts ${it.seconds} s")
            if (duration != null && it.seconds > duration + 1.0) {
                problem(where, "lap ${it.number} of ${fmt(it.seconds)} s in a session of ${fmt(duration)} s")
            }
        }
        val fastest = laps.filter { it.fastest }
        val counted = laps.filter { it.counts }
        if (counted.isNotEmpty() && fastest.size != 1) problem(where, "${fastest.size} fastest laps")
        fastest.singleOrNull()?.let { f ->
            if (review.bestLapSeconds != f.seconds) {
                problem(where, "best ${review.bestLapSeconds} is not lap ${f.number}")
            }
            if (abs(f.deltaSeconds) > 1e-6) problem(where, "fastest lap ${fmt(f.deltaSeconds)} s behind itself")
            counted.filter { it.seconds < f.seconds - 1e-6 }.forEach {
                problem(where, "counted lap ${it.number} (${fmt(it.seconds)}) faster than the fastest ${f.number}")
            }
            counted.filter { it.deltaSeconds < -1e-6 }.forEach {
                problem(where, "counted lap ${it.number} ahead of the fastest by ${fmt(-it.deltaSeconds)} s")
            }
        }
        val theoretical = review.theoreticalBestSeconds
        val best = review.bestLapSeconds
        if (theoretical != null && best != null && theoretical > best + 1e-3) {
            problem(where, "theoretical best ${fmt(theoretical)} slower than the best lap ${fmt(best)}")
        }
        // The whole session: G-forces and runs on Summary, the drive on the map without laps.
        reviewed.whole.gForces?.let { gg(where, "whole session G-G", it) }
        text(where, "acceleration runs", reviewed.whole.acceleration)
        reviewed.drive?.let { d ->
            val figures = listOfNotNull(
                d.distanceMeters, d.durationSeconds, d.movingSeconds, d.averageMovingKmh, d.topSpeedKmh,
            )
            if (figures.any { !it.isFinite() || it < 0.0 }) problem(where, "drive figures $figures")
            chart(where, "drive speed", d.speed)
            text(where, "drive map", d.map)
        }
        if (laps.isEmpty()) {
            if (reviewed.defaultScreen() != null) problem(where, "an analysis screen without laps")
            return
        }
        // Summary, Laps, Corners and Car: the screen Android and iOS open on.
        val screen = try {
            reviewed.defaultScreen()
        } catch (e: Exception) {
            problem(where, "Summary threw $e")
            return
        }
        if (screen == null) {
            if (counted.isNotEmpty()) problem(where, "no analysis screen with ${counted.size} counted laps")
            return
        }
        summary(where, review.bestLapSeconds, laps.size, screen)
        val indices = if (everyLap) laps.indices else listOf(0, laps.size / 2, laps.size - 1).distinct()
        for (i in indices) {
            val lap = "lap ${laps[i].number}"
            try {
                reviewed.screen(i)?.let { s ->
                    text(where, "$lap screen", s)
                    s.map?.let { m ->
                        m.gg?.let { gg(where, "$lap G-G", it) }
                        m.referenceGg?.let { gg(where, "$lap reference G-G", it) }
                    }
                }
                reviewed.compare(i)?.let { c ->
                    if (!c.lengthMeters.isFinite() || c.lengthMeters <= 0.0) {
                        problem(where, "$lap compare length ${c.lengthMeters}")
                    }
                    chart(where, "$lap delta", c.delta)
                    chart(where, "$lap reference speed", c.referenceSpeed)
                    chart(where, "$lap speed", c.otherSpeed)
                }
            } catch (e: Exception) {
                problem(where, "$lap Compare/Map threw $e")
            }
        }
        // Corners: every pass through each corner the coach can be told about.
        for (corner in reviewed.flaggableCorners) {
            try {
                text(where, "every pass ${corner.name}", reviewed.everyPass(corner.id, emptyList(), "", { false }))
                reviewed.reviewStart(corner.id)?.let {
                    if (!it.isFinite()) problem(where, "${corner.name} review start $it")
                }
            } catch (e: Exception) {
                problem(where, "every pass of ${corner.name} threw $e")
            }
        }
    }

    private fun summary(where: String, best: Double?, laps: Int, screen: AnalysisUi) {
        text(where, "Summary", screen)
        if (screen.laps.size != laps) problem(where, "Summary lists ${screen.laps.size} laps, the list $laps")
        if (best != null && abs(screen.bestLapMillis - (best * 1000).roundToLong()) > 1) {
            problem(where, "Summary best ${screen.bestLapMillis} ms, the list ${fmt(best)} s")
        }
        val bestMs = screen.bestLapMillis
        val theoretical = screen.theoreticalMillis
        val realistic = screen.realisticMillis
        if (theoretical != null && theoretical > bestMs + 1) {
            problem(where, "raw theoretical $theoretical ms > best $bestMs ms")
        }
        if (realistic != null && realistic > bestMs + 1) {
            problem(where, "realistic optimal $realistic ms > best $bestMs ms")
        }
        if (realistic != null && theoretical != null && realistic < theoretical - 1) {
            problem(where, "realistic optimal $realistic ms faster than the raw theoretical $theoretical ms")
        }
        screen.gainSeconds?.let { g ->
            if (theoretical != null && abs(g - (bestMs - theoretical) / 1000.0) > 0.002) {
                problem(where, "gain ${fmt(g)} s is not best - theoretical")
            }
            if (g < -1e-3) problem(where, "negative gain $g")
        }
        screen.realisticGainSeconds?.let { g -> if (g < -1e-3) problem(where, "negative realistic gain $g") }
        if (screen.focus.size > 3) problem(where, "${screen.focus.size} focus areas")
        val primaries = screen.focus.count { it.primary }
        if (primaries > 1) problem(where, "$primaries primary focus areas")
        if (screen.focus.isNotEmpty() && screen.focus.none { it.primary }) problem(where, "focus without a primary")
        screen.focus.forEach { f ->
            if (f.observation.isBlank()) problem(where, "focus ${f.name} says nothing")
        }
        screen.gains.zipWithNext().forEach { (a, b) ->
            val x = a.lossSeconds
            val y = b.lossSeconds
            if (x != null && y != null && y > x + 1e-9) problem(where, "where time goes not largest first: $x then $y")
        }
        screen.gForces?.let { gg(where, "Summary G-G", it) }
    }

    private fun progress(where: String, reviewed: ReviewedSession, earlier: List<Recorded>) {
        val track = reviewed.review.track ?: return
        var previous: PreviousLaps? = null
        val sessions = earlier.map { e ->
            val session = telemetrySessionOf(e.data, track)
            val laps = deriveSourceLapSession(session)
            if (previous == null && laps.fastestLapIndex != null) previous = PreviousLaps(session, laps)
            EarlierSession(e.name, laps.copy(lapTraces = emptyList()))
        }
        @Suppress("TooGenericExceptionCaught")
        try {
            val progress = reviewed.progress(sessions, previous, false)
            if (progress == null) problem(where, "no progress at its track")
            text(where, "progress", progress)
        } catch (e: Exception) {
            problem(where, "progress threw $e")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun trackDay(sessions: List<Recorded>) {
        val where = "${sessions.first().name} track day"
        try {
            val track = sessions.first().track
            val lines = sessions.map {
                val session = telemetrySessionOf(it.data, track)
                LineSession(session, deriveSourceLapSession(session), it.name, it.data.id)
            }
            val analyze = { s: LineSession -> analyzeKeepingIdentity(s.session, track.identity, 0L) { _, _ -> null } }
            val analysis = analyzeDay(lines, analyze)
            if (analysis == null) {
                problem(where, "no track day of ${sessions.size} sessions")
                return
            }
            val ui = trackDayUi(analysis, track.displayName, "Fri 29 Aug")
            text(where, "page", ui)
            if (ui.sessions.size != sessions.size) problem(where, "${ui.sessions.size} sessions of ${sessions.size}")
        } catch (e: Exception) {
            problem(where, "threw $e")
        }
    }

    private fun gg(where: String, what: String, trace: GgTrace) {
        val bad = trace.points.firstOrNull {
            !it.lateralG.isFinite() || !it.longitudinalG.isFinite() ||
                abs(it.lateralG) > MaxG || abs(it.longitudinalG) > MaxG
        }
        if (bad != null) problem(where, "$what point $bad")
    }

    private fun chart(where: String, what: String, runs: List<List<ChartPoint>>) {
        val bad = runs.flatten().firstOrNull { !it.distanceMeters.isFinite() || !it.value.isFinite() }
        if (bad != null) problem(where, "$what point $bad")
    }

    /** Words a screen must never show: a number that is not one. */
    private fun text(where: String, what: String, model: Any?) {
        val shown = model?.toString() ?: return
        for (word in listOf("NaN", "Infinity")) {
            val at = shown.indexOf(word)
            if (at >= 0) {
                val around = shown.substring(maxOf(0, at - 80), minOf(shown.length, at + 20))
                problem(where, "$what shows $word: …$around")
            }
        }
    }

    private fun durationSeconds(data: SessionData): Double? = times(data)?.let { (a, b) -> (b - a) / 1e6 }

    private companion object {
        /** No road car on road tyres pulls more than this; a larger G is a fault in the figures. */
        const val MaxG = 2.5

        fun fmt(v: Double) = String.format(Locale.ROOT, "%.3f", v)

        fun times(data: SessionData): Pair<Long, Long>? {
            val sets = data.sampleSets
            if (sets.isEmpty()) return null
            return sets.minOf { it.tMicros } to sets.maxOf { it.tMicros }
        }

        /** The samples between [from] and [to] of the recording's time. */
        fun cut(data: SessionData, from: Double, to: Double): SessionData {
            val (a, b) = times(data) ?: return data
            val start = a + ((b - a) * from).roundToLong()
            val end = a + ((b - a) * to).roundToLong()
            return data.copy(sampleSets = data.sampleSets.filter { it.tMicros in start..end })
        }

        fun firstSeconds(data: SessionData, seconds: Double): SessionData {
            val (a, _) = times(data) ?: return data
            return data.copy(sampleSets = data.sampleSets.filter { it.tMicros <= a + (seconds * 1e6).roundToLong() })
        }

        /** No position or speed for [seconds] from [at] of the recording; other channels go on. */
        fun gpsLoss(data: SessionData, at: Double, seconds: Double): SessionData {
            val (a, b) = times(data) ?: return data
            val start = a + ((b - a) * at).roundToLong()
            return withoutGps(data) { it in start until start + (seconds * 1e6).roundToLong() }
        }

        fun repeatedLoss(data: SessionData, seconds: Double, every: Double): SessionData {
            val (a, _) = times(data) ?: return data
            val period = (every * 1e6).roundToLong()
            val loss = (seconds * 1e6).roundToLong()
            return withoutGps(data) { t -> (t - a) % period in (period - loss) until period }
        }

        private val Gnss =
            setOf(GnssChannels.LAT.id, GnssChannels.LON.id, GnssChannels.SPEED_KMH.id, GnssChannels.HEADING.id)

        fun withoutGps(data: SessionData, lost: (Long) -> Boolean) = data.copy(
            sampleSets = data.sampleSets.mapNotNull { s ->
                if (!lost(s.tMicros)) return@mapNotNull s
                val rest = s.values.filter { it.channelId !in Gnss }
                if (rest.isEmpty()) null else SampleSet(s.tMicros, rest)
            },
        )

        /**
         * The car standing still for [seconds] in the middle of the recording (a red flag, a pit
         * stop with the logger on): at its slowest fix between [from] and [to] of the samples, that
         * fix repeated at 10 Hz with no speed, the rest of the drive later.
         */
        fun stopped(data: SessionData, from: Double, to: Double, seconds: Double): SessionData {
            val sets = data.sampleSets.sortedBy { it.tMicros }
            fun speed(s: SampleSet) = s.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            val i = (sets.size * from).toInt().until((sets.size * to).toInt())
                .filter { k -> speed(sets[k]) != null && sets[k].values.any { it.channelId == GnssChannels.LAT.id } }
                .minByOrNull { k -> checkNotNull(speed(sets[k])) } ?: return data
            val here = sets[i]
            val pause = (seconds * 1e6).roundToLong()
            val still = (1..(seconds * 10).toInt()).map { k ->
                SampleSet(
                    here.tMicros + k * 100_000L,
                    here.values.map {
                        if (it.channelId == GnssChannels.SPEED_KMH.id) ChannelValue(it.channelId, 0.0) else it
                    },
                )
            }
            val later = sets.drop(i + 1).map { SampleSet(it.tMicros + pause, it.values) }
            return data.copy(sampleSets = sets.take(i + 1) + still + later)
        }

        fun everyNth(data: SessionData, n: Int) =
            data.copy(sampleSets = data.sampleSets.filterIndexed { i, _ -> i % n == 0 })

        fun doubled(data: SessionData) = data.copy(sampleSets = data.sampleSets.flatMap { listOf(it, it) })
    }
}
