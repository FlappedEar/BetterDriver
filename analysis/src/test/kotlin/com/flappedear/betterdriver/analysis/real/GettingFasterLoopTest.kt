@file:Suppress(
    "MagicNumber", // Arek's sessions and what the loop does with them today.
    "LongParameterList", "CyclomaticComplexMethod", // One record per link of the loop; one check per link.
)

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.SessionAnalysis
import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.coaching.CoachedSession
import com.flappedear.betterdriver.analysis.coaching.FocusAttempt
import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.coaching.coachingHistory
import com.flappedear.betterdriver.analysis.coaching.sessionCoachingRecord
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.focus.NextSessionPlan
import com.flappedear.betterdriver.analysis.focus.nextSessionPlan
import com.flappedear.betterdriver.analysis.line.LineAnalysis
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.segmentLine
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.Cue
import com.flappedear.betterdriver.coach.CueKind
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.MinLossMicros
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.advise
import com.flappedear.betterdriver.coach.buildReference
import com.flappedear.betterdriver.coach.coachFixes
import com.flappedear.betterdriver.coach.identified
import com.flappedear.betterdriver.coach.lapFrame
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.coach.tracedLaps
import com.flappedear.betterdriver.recorder.Confidence
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackRevision
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The getting-faster loop (FBBD-309, FBBD-311) run end to end on Arek's own track days, with no
 * test drive. Each session is analysed as the app analyses it: where the time is against every
 * earlier session's laps (FBBD-306) and what to work on next (FBBD-20). The next session of the day
 * is then replayed through the coach at Focus, with the reference and focus the app would hand it,
 * and its coaching history (FBBD-180) says whether the focus corners got faster.
 *
 * A link that breaks fails the build: a session without a focus, a focus the coach is not handed,
 * a number said without High confidence, a focus the next session does not measure. Where the loop
 * is known to fall short today, the shortfall is counted and may only shrink ([Known]); the threads
 * that close it lower the count. Each day's chain is written to build/reports/replay-drive, for the
 * pull request's checks.
 */
class GettingFasterLoopTest {
    @TempDir
    lateinit var tmp: File

    private val store by lazy { SessionStore(File(tmp, "sessions")) }

    /**
     * Where the loop falls short on a day today, never to grow: focus corners the coach has no
     * corner for ([uncoachable]), and focus corners where the next session lost time against the
     * coach's target on two passes or more and heard nothing there ([silent]).
     */
    private class Known(val uncoachable: Int, val silent: Int)

    /** One analysed session, and what it hands the next. */
    private class Step(
        val label: String,
        val data: SessionData,
        val session: TelemetrySession,
        val analysis: SessionAnalysis,
        val plan: NextSessionPlan,
        val line: LineAnalysis?,
        val focus: SessionFocusRecord,
    )

    /** One focus corner of a session, in the next: the coach's corners there, its passes and cues, and the proof. */
    private class FocusCorner(
        val target: FocusTarget,
        val coachCorners: List<Int>,
        val passes: Int,
        /** Passes at least [MinLossMicros] slower than the coach's target. */
        val losing: Int,
        /** Of those, the ones the coach has brake or speed advice for. */
        val advisable: Int,
        val cues: Int,
        val attempt: FocusAttempt?,
    ) {
        val silent: Boolean get() = coachCorners.isNotEmpty() && losing >= 2 && cues == 0
    }

    /** One turn of the loop: [from]'s analysis, the coach in [to], and what [to] measured. */
    private class Turn(val from: Step, val to: Step, val corners: List<FocusCorner>, val cues: List<Cue>)

    private fun day(files: List<String>, track: Track): List<Step> {
        val steps = mutableListOf<Step>()
        var identity: TrackRevision? = null
        for (name in files) {
            val data = store.read(RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id)
            val session = telemetrySessionOf(data, track)
            // The track keeps its corner identity from one session to the next (FBBD-172).
            val analysis = analyzeSession(session, identity = identity)
            identity = analysis.identity ?: identity
            val others = steps.map { LineSession(it.session, it.analysis.laps, it.label) }
            val plan = nextSessionPlan(analysis)
            val line = analyzeLine(analysis, session, others)
            steps += Step(label(name), data, session, analysis, plan, line, focusRecord(track, data, analysis, plan))
        }
        return steps
    }

    private fun label(name: String) = name.substringAfter("2026-").substringBefore("-racechrono")

    /** The focus as the app keeps it for the next session's coach (focusRecord in the app's NextSessionFocus). */
    private fun focusRecord(track: Track, data: SessionData, analysis: SessionAnalysis, plan: NextSessionPlan) =
        SessionFocusRecord(
            trackId = track.id,
            sessionId = data.id,
            sessionStartUtcMillis = data.info.startUtcMillis,
            targets = plan.focuses.map { f ->
                val segment = analysis.segments?.segments?.firstOrNull { it.id == f.segmentId }
                val line = analysis.axis?.let { axis -> segment?.let { segmentLine(axis, it) } }.orEmpty()
                FocusTarget(
                    id = FocusTarget.idOf(data.id, f.segmentId),
                    role = f.role.code,
                    segmentId = f.segmentId,
                    cornerId = f.segmentId.takeIf { plan.identityRevision != null },
                    identityRevision = plan.identityRevision,
                    name = f.name,
                    kind = f.area.kind.code,
                    confidence = f.confidence.level.name,
                    timeAvailableSeconds = f.timeAvailableSeconds,
                    timeConfidence = f.timeConfidence.name,
                    lats = line.map { it.latitudeDegrees },
                    lons = line.map { it.longitudeDegrees },
                )
            },
        )

    /** The coaching history record of [step], with what its coach logged. */
    private fun record(track: Track, step: Step, events: List<RecorderEvent>): SessionCoachingRecord? =
        sessionCoachingRecord(
            step.analysis, track.id, step.data.id, step.data.info.startUtcMillis,
            CoachedSession(CoachEvents.focusSource(events), CoachEvents.cuesByCorner(events)),
        )

    private fun loop(files: List<String>, track: Track): Pair<List<Step>, List<Turn>> {
        val steps = day(files, track)
        val records = listOfNotNull(record(track, steps.first(), emptyList())).toMutableList()
        val turns = (1 until steps.size).map { k ->
            val from = steps[k - 1]
            val to = steps[k]
            // The coach of the next session at the track, as the app starts it: the track with its
            // corner identity, a reference from every session so far, the last one's focus.
            val at = track.copy(identity = from.analysis.identity)
            val reference = buildReference(
                at,
                steps.subList(0, k).map { SessionLaps(it.data.id, tracedLaps(at, it.data.coachFixes())) },
            )
            val coach = CoachSession(
                CoachSettings(Verbosity.Focus), at, reference, sameDay = true, focus = from.focus.picks(),
            )
            val focusEvent = RecorderEvent(
                0, CoachEvents.FOCUS,
                CoachEvents.focusText(from.data.id, from.focus.targets.mapNotNull { it.cornerId }),
            )
            val events = listOf(focusEvent) + to.data.sampleSets.mapNotNull { it.toCoachFix() }
                .flatMap { f -> coach.onFix(f, f.tMicros) { true }.events }
                .map { RecorderEvent(it.tMicros, it.kind, it.text) }
            val cues = events.filter { it.kind == CoachEvents.CUE }
                .mapNotNull { CoachEvents.parseCue(it.tMicros, it.text) }
            record(track, to, events)?.let { records += it }
            val attempts = coachingHistory(to.data.id, records).attempts
            Turn(from, to, focusCorners(at, reference, from, to, cues, attempts), cues)
        }
        return steps to turns
    }

    /** What became of each focus corner of [from] in [to]: against the reference the coach starts [to] with. */
    private fun focusCorners(
        at: Track,
        reference: CoachReference?,
        from: Step,
        to: Step,
        cues: List<Cue>,
        attempts: List<FocusAttempt>,
    ): List<FocusCorner> {
        val map = reference?.let { r -> lapFrame(at)?.let { identified(r.map(), it, at.identity) } }
        val laps = tracedLaps(at, to.data.coachFixes())
        val passes = map?.let { m -> laps.flatMap { m.passes(it.trace, it.lap.number) } }.orEmpty()
        return from.focus.targets.map { t ->
            val corners = map?.corners.orEmpty().filter { it.cornerId != null && it.cornerId == t.cornerId }
            var count = 0
            var losing = 0
            var advisable = 0
            for (c in corners) {
                // The coach aims at a time driven twice (FBBD-177): the second fastest pass.
                val target = reference?.targets?.filter { it.corner == c.number }?.getOrNull(1)
                val here = passes.filter { it.corner == c.number }
                count += here.size
                if (target == null) continue
                val slow = here.filter { it.timeMicros - target.timeMicros >= MinLossMicros }
                losing += slow.size
                advisable += slow.count { advise(it, target, c) != null }
            }
            FocusCorner(
                t, corners.map { it.number }, count, losing, advisable,
                cues.count { it.kind == CueKind.Corner && it.cornerId == t.cornerId },
                attempts.firstOrNull { it.ask.cornerId == t.cornerId },
            )
        }
    }

    /** Every link of the loop on one day, and the shortfalls against [known]. */
    private fun check(name: String, files: List<String>, track: Track, known: Known) {
        val (steps, turns) = loop(files, track)
        report(name, steps, turns)
        val problems = mutableListOf<String>()
        // 1. Find: every session says where the time is and what to work on next.
        for (s in steps) {
            val line = s.line
            if (line == null || line.corners.isEmpty()) problems += "${s.label}: no line analysis"
            if (s.plan.primary == null) problems += "${s.label}: no next-session focus"
            if (s.focus.targets.any { it.cornerId == null || it.lats.isEmpty() }) {
                problems += "${s.label}: a focus without its CornerId or line, so the coach cannot place it"
            }
            // The focus and the line analysis speak of the same corners.
            val corners = line?.corners?.map { it.segmentId }.orEmpty().toSet()
            s.plan.focuses.filter { it.segmentId !in corners }
                .forEach { problems += "${s.label}: focus ${it.name} is not a corner of the line analysis" }
        }
        for (t in turns) {
            val turn = "${t.from.label} -> ${t.to.label}"
            // 2. Coach: no number without High confidence (FBBD-177).
            t.cues.filter { it.kind == CueKind.Corner && it.confidence != Confidence.HIGH }
                .filter { c -> c.text.replace("Turn ${c.turn}", "").any(Char::isDigit) }
                .forEach { problems += "$turn: '${it.text}' says a number at ${it.confidence}" }
            // 3. Prove: the next session measures every focus corner, and knows the coach worked on it.
            for (c in t.corners) {
                val a = c.attempt
                when {
                    a == null -> problems += "$turn: ${c.target.name} not measured"
                    a.verdict == FocusVerdict.NotComparable -> problems += "$turn: ${c.target.name} not comparable"
                    !a.coached -> problems += "$turn: ${c.target.name} not known as coached"
                }
            }
        }
        val all = turns.flatMap { it.corners }
        val uncoachable = all.count { it.coachCorners.isEmpty() }
        val silent = all.count { it.silent }
        if (uncoachable > known.uncoachable) {
            problems += "$uncoachable focus corners without a coach corner, was ${known.uncoachable}"
        }
        if (silent > known.silent) problems += "$silent silent focus corners, was ${known.silent}"
        if (all.none { it.attempt?.verdict == FocusVerdict.Improved }) {
            problems += "no focus corner got faster all day"
        }
        assertTrue(problems.isEmpty(), "$name:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the loop runs through Arek's six sessions at Jastrząb (29 Aug 2026)`() =
        check(
            "Jastrząb 29 Aug", RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack(),
            Known(uncoachable = 2, silent = 4),
        )

    @Test
    fun `the loop runs through Arek's three sessions at Silesia Ring (27 Sep 2026)`() =
        check(
            "Silesia Ring 27 Sep", RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack(),
            Known(uncoachable = 0, silent = 3),
        )

    /** Each turn of the loop as a Markdown page, with the drive replay's in the pull request's checks. */
    private fun report(name: String, steps: List<Step>, turns: List<Turn>) {
        val out = StringBuilder()
        out.appendLine("### Getting-faster loop: $name").appendLine()
        out.appendLine("| Session | Best lap | Time to find | Most in | Focus next |")
        out.appendLine("| --- | --- | --- | --- | --- |")
        for (s in steps) {
            val l = s.line
            out.appendLine(
                "| ${s.label} | ${fmt(l?.referenceLapSeconds)} | ${fmt(l?.totalGainSeconds)} s | " +
                    l?.ranked?.take(3)?.joinToString { "${it.name} ${fmt(it.gainSeconds)}" }.orEmpty() + " | " +
                    s.plan.focuses.joinToString { "${it.name} ${fmt(it.timeAvailableSeconds)}" } + " |",
            )
        }
        out.appendLine()
        out.appendLine(
            "| Turn | Focus corner | Coach corners | Passes | Losing | Brake/speed advice | Cues | Next session |",
        )
        out.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        for (t in turns) {
            for (c in t.corners) {
                val a = c.attempt
                val proof = a?.let { "${it.verdict} ${fmt(it.gainSeconds)} s" } ?: "-"
                out.appendLine(
                    "| ${t.from.label} → ${t.to.label} | ${c.target.role} ${c.target.name} | " +
                        "${c.coachCorners.joinToString().ifEmpty { "none" }} | ${c.passes} | ${c.losing} | " +
                        "${c.advisable} | ${c.cues} | $proof |",
                )
            }
        }
        val text = out.toString()
        println(text)
        File("build/reports/replay-drive").apply { mkdirs() }
            .resolve("loop-${name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")}.md")
            .writeText(text)
    }

    private fun fmt(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
}
