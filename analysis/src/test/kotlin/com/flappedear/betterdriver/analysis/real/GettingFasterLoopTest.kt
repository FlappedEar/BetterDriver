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
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.LineProof
import com.flappedear.betterdriver.analysis.line.ProofVerdict
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.lineProof
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.segments.segmentLine
import com.flappedear.betterdriver.coach.CoachEvents
import com.flappedear.betterdriver.coach.CoachReference
import com.flappedear.betterdriver.coach.CoachSession
import com.flappedear.betterdriver.coach.CoachSettings
import com.flappedear.betterdriver.coach.CornerMap
import com.flappedear.betterdriver.coach.CornerPick
import com.flappedear.betterdriver.coach.Cue
import com.flappedear.betterdriver.coach.CueKind
import com.flappedear.betterdriver.coach.FocusTarget
import com.flappedear.betterdriver.coach.MinLossMicros
import com.flappedear.betterdriver.coach.PlannedTarget
import com.flappedear.betterdriver.coach.PlannedTargets
import com.flappedear.betterdriver.coach.SessionFocusRecord
import com.flappedear.betterdriver.coach.SessionLaps
import com.flappedear.betterdriver.coach.TracedLap
import com.flappedear.betterdriver.coach.Verbosity
import com.flappedear.betterdriver.coach.advise
import com.flappedear.betterdriver.coach.aim
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
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The getting-faster loop (FBBD-309, FBBD-311) run end to end on Arek's own track days, with no
 * test drive. Each session is analysed as the app analyses it: where the time is against every
 * earlier session's laps, kept as the track's line plan (FBBD-306), and what to work on next
 * (FBBD-20). The next session of the day is then replayed through the coach at Focus, with the
 * reference and focus the app would hand it, and its coaching history (FBBD-180) and the line
 * plan's proof (FBBD-310) say whether the focus corners got faster.
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
     * coach's target on two passes or more and heard nothing there ([silent]), and corners whose
     * coach target is another lap than the line plan's ([otherTarget]: one target everywhere, FBBD-306),
     * and corners of the line plan's focus the coach said nothing about in the next session ([unspoken]).
     */
    private class Known(val uncoachable: Int, val silent: Int, val otherTarget: Int, val unspoken: Int)

    /** One analysed session, and what it hands the next. */
    private class Step(
        val label: String,
        val data: SessionData,
        val session: TelemetrySession,
        val analysis: SessionAnalysis,
        val plan: NextSessionPlan,
        val line: LineAnalysis?,
        /** The track's line plan after this session (FBBD-306): what the Map tab, the coach and the 3D export read. */
        val linePlan: LinePlan?,
        val focus: SessionFocusRecord,
    )

    /** One corner the coach of the next session worked on: its corners there, their passes and cues. */
    private class Coached(
        val coachCorners: List<Int>,
        val passes: Int,
        /** Passes at least [MinLossMicros] slower than the coach's target. */
        val losing: Int,
        /** Of those, the ones the coach has brake or speed advice for. */
        val advisable: Int,
        val cues: Int,
    ) {
        val silent: Boolean get() = coachCorners.isNotEmpty() && losing >= 2 && cues == 0
    }

    /** One focus corner of a session (FBBD-20), in the next: how the coach did there, and the proof. */
    private class FocusCorner(val target: FocusTarget, val coached: Coached, val attempt: FocusAttempt?) {
        val coachCorners get() = coached.coachCorners
        val passes get() = coached.passes
        val losing get() = coached.losing
        val advisable get() = coached.advisable
        val cues get() = coached.cues
        val silent get() = coached.silent
    }

    /** A corner of [from]'s line plan and the lap the coach of the next session aims at there. */
    private class Target(val name: String, val plan: String?, val coach: String?) {
        val same: Boolean get() = plan != null && plan == coach
    }

    /**
     * One turn of the loop: [from]'s analysis, the coach in [to], what [to] measured, the line plan's
     * proof ([proof], FBBD-310) and the targets of the plan against the coach's ([targets]).
     */
    private class Turn(
        val from: Step,
        val to: Step,
        val corners: List<FocusCorner>,
        val cues: List<Cue>,
        val proof: LineProof?,
        val targets: List<Target>,
        /** The line plan's focus corners when the coach worked on them (UI-174 A), by CornerId. */
        val planned: Map<String, Coached>?,
    ) {
        /** The corners the coach worked on: the plan's focus when it had one, else the session focus. */
        val worked: List<Coached> get() = planned?.values?.toList() ?: corners.map { it.coached }
    }

    private fun day(files: List<String>, track: Track): List<Step> {
        val steps = mutableListOf<Step>()
        var identity: TrackRevision? = null
        for (name in files) {
            val data = store.read(RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id)
            val session = telemetrySessionOf(data, track)
            // The track keeps its corner identity from one session to the next (FBBD-172).
            val analysis = analyzeSession(session, identity = identity)
            identity = analysis.identity ?: identity
            val others = steps.map { LineSession(it.session, it.analysis.laps, it.label, it.data.id) }
            val plan = nextSessionPlan(analysis)
            val line = analyzeLine(analysis, session, others)
            // Made as the app makes it when the session is analysed (lineState in the app's LineSessions).
            val linePlan = line?.let { l ->
                analysis.axis?.origin?.let { origin ->
                    val revision = analysis.identity?.number?.takeIf { analysis.identified }
                    linePlan(l, origin, track.id, data.id, data.info.startUtcMillis, revision)
                }
            }
            val focus = focusRecord(track, data, analysis, plan)
            steps += Step(label(name), data, session, analysis, plan, line, linePlan, focus)
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
            CoachedSession(
                CoachEvents.focusSource(events), CoachEvents.cuesByCorner(events), CoachEvents.planCorners(events),
            ),
        )

    /**
     * [plan]'s focus as the app gives it to the coach (planFocusOf in CoachService): its corners as
     * coach picks, most time to find first, and the time to find in each by CornerId; null without one.
     */
    private fun planFocus(plan: LinePlan): Pair<List<CornerPick>, Map<String, Double>>? {
        val corners = plan.focus().filter { it.cornerId != null && it.target.line.isNotEmpty() }
        if (plan.identityRevision == null || corners.isEmpty()) return null
        val picks = corners.map { c ->
            CornerPick(
                c.name, c.target.line.map { it.latitude }, c.target.line.map { it.longitude }, plan.sessionId,
                c.cornerId, plan.identityRevision,
            )
        }
        return picks to corners.associate { c ->
            c.cornerId!! to
                (c.gainSeconds.takeIf { it > 0.0 } ?: c.brakeRoom?.seconds ?: c.gear?.seconds ?: c.gainSeconds)
        }
    }

    /** [plan]'s targets as the app gives them to the coach (CoachReferences.plannedTargets). */
    private fun plannedTargets(plan: LinePlan) = PlannedTargets(
        plan.sessionId,
        plan.corners.mapNotNull { c ->
            c.cornerId?.let {
                PlannedTarget(
                    it, c.target.sessionId, c.target.lapSeconds,
                    c.brakeRoom?.laterMeters ?: 0, c.brakeRoom?.seconds ?: 0.0, c.gear?.gear,
                )
            }
        },
        plan.gearbox,
    )

    private fun loop(files: List<String>, track: Track): Pair<List<Step>, List<Turn>> {
        val steps = day(files, track)
        val records = listOfNotNull(record(track, steps.first(), emptyList())).toMutableList()
        val turns = (1 until steps.size).map { k ->
            val from = steps[k - 1]
            val to = steps[k]
            // The coach of the next session at the track, as the app starts it: the track with its
            // corner identity, a reference from every session so far aiming where the last one's line
            // plan does (FBBD-307), the last one's focus.
            val at = track.copy(identity = from.analysis.identity)
            val reference = buildReference(
                at,
                steps.subList(0, k).map { SessionLaps(it.data.id, tracedLaps(at, it.data.coachFixes())) },
                from.linePlan?.let(::plannedTargets),
            )
            // The focus as the app gives it (planFocusOf in the app's CoachService, UI-174 A): the line
            // plan's corners with the most time to find, else the last session's focus (FBBD-20).
            val planned = from.linePlan?.let(::planFocus)
            val coach = CoachSession(
                CoachSettings(Verbosity.Focus), at, reference, sameDay = true,
                focus = planned?.first ?: from.focus.picks(), focusSeconds = planned?.second.orEmpty(),
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
            val proof = from.linePlan?.let { before ->
                to.linePlan?.let { lineProof(before, it, CoachEvents.cuesByCorner(events)) }
            }
            val measure = Measure(at, reference, to, cues)
            Turn(
                from, to,
                from.focus.targets.map { t ->
                    FocusCorner(t, measure.at(t.cornerId), attempts.firstOrNull { it.ask.cornerId == t.cornerId })
                },
                cues, proof, targets(at, reference, steps.subList(0, k), from.linePlan),
                planned?.first?.mapNotNull { it.cornerId }?.associateWith { measure.at(it) },
            )
        }
        return steps to turns
    }

    /** How the coach of [to] did at a corner, against the reference it starts [to] with. */
    private class Measure(
        private val at: Track,
        private val reference: CoachReference?,
        to: Step,
        private val cues: List<Cue>,
    ) {
        private val map = reference?.let { r -> lapFrame(at)?.let { identified(r.map(), it, at.identity) } }
        private val passes = map?.let { m ->
            tracedLaps(at, to.data.coachFixes()).flatMap { m.passes(it.trace, it.lap.number) }
        }
            .orEmpty()

        /** The coach's corners in the track corner [cornerId], their passes, losses and cues. */
        fun at(cornerId: String?): Coached {
            val corners = map?.corners.orEmpty().filter { it.cornerId != null && it.cornerId == cornerId }
            var count = 0
            var losing = 0
            var advisable = 0
            for (c in corners) {
                // The coach aims where the line plan does, else at a time driven twice (FBBD-177).
                val target = reference?.aim(c.number)
                val here = passes.filter { it.corner == c.number }
                count += here.size
                if (target == null) continue
                val slow = here.filter { it.timeMicros - target.timeMicros >= MinLossMicros }
                losing += slow.size
                advisable += slow.count { advise(it, target, c) != null }
            }
            val said = cues.count { it.kind == CueKind.Corner && it.cornerId == cornerId }
            return Coached(corners.map { it.number }, count, losing, advisable, said)
        }
    }

    /**
     * For every corner of [plan], the session and lap of its target, and of the coach's target there
     * (its second fastest pass, FBBD-177) in a reference from [earlier]: the plan's sessions. A lap is
     * known by the coach's number for it: the analysis's lap is the coach's lap of the same session
     * with the same time, since the two number laps each their own way.
     */
    private fun targets(at: Track, reference: CoachReference?, earlier: List<Step>, plan: LinePlan?): List<Target> {
        val map = reference?.let { r -> lapFrame(at)?.let { identified(r.map(), it, at.identity) } }
            ?: return emptyList()
        val laps = earlier.flatMap { s -> tracedLaps(at, s.data.coachFixes()).map { s to it } }
        return plan?.corners.orEmpty().filter { it.cornerId != null }.map { c ->
            val coach = map.corners.filter { it.cornerId == c.cornerId }.map { corner ->
                val target = reference.aim(corner.number)
                // A braking point beyond his best (FBBD-312) aims at the plan's pass made faster by its step.
                val step = c.brakeRoom?.let { (it.seconds * 1e6).roundToLong() }?.takeIf { corner.braked } ?: 0L
                target?.let { t -> source(map, laps, t.lap, t.timeMicros + step, corner.number) } ?: "none"
            }.distinct().singleOrNull()
            Target(c.name, lapOf(laps, c.target.sessionId, c.target.lapSeconds), coach)
        }
    }

    /**
     * The session and lap time of the pass through [corner] on lap [lap] that took [micros]: the
     * reference keeps its path to the centimetre, so a pass measured again is a few tenths of a
     * millisecond off ([SamePassMicros]).
     */
    private fun source(
        map: CornerMap,
        laps: List<Pair<Step, TracedLap>>,
        lap: Int,
        micros: Long,
        corner: Int,
    ): String? = laps.filter { it.second.lap.number == lap }
        .mapNotNull { (s, t) ->
            map.passes(t.trace, t.lap.number).firstOrNull { it.corner == corner }
                ?.let { abs(it.timeMicros - micros) to "${s.data.id} lap ${t.lap.number}" }
        }
        .filter { it.first <= SamePassMicros }
        .minByOrNull { it.first }?.second

    /**
     * The coach's lap of session [sessionId] that the analysis timed at [seconds]: the one closest in
     * time, within [SameLapSeconds], as each times laps from its own fixes.
     */
    private fun lapOf(laps: List<Pair<Step, TracedLap>>, sessionId: String, seconds: Double): String? =
        laps.filter { it.first.data.id == sessionId }
            .map { (_, t) -> abs(t.lap.timeMicros / 1e6 - seconds) to t.lap.number }
            .filter { it.first <= SameLapSeconds }
            .minByOrNull { it.first }?.let { "$sessionId lap ${it.second}" }

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
            // 2. Coach: the out-lap says the plan's focus corners once (UI-174 A), the way the coach numbers them.
            val plans = t.cues.filter { it.kind == CueKind.Plan }
            if (t.from.linePlan?.focus()?.isNotEmpty() == true && plans.size != 1) {
                problems += "$turn: ${plans.size} out-lap plan cues, 1 expected"
            }
            // No number without High confidence (FBBD-177).
            t.cues.filter { it.kind == CueKind.Corner && it.confidence != Confidence.HIGH }
                .filter { c -> c.text.replace("Turn ${c.turn}", "").any(Char::isDigit) }
                .forEach { problems += "$turn: '${it.text}' says a number at ${it.confidence}" }
            // 3. Prove (FBBD-310): the line plan's focus is measured in the next session.
            val proof = t.proof
            if (proof == null && t.from.linePlan?.focus()?.isNotEmpty() == true) problems += "$turn: no line proof"
            proof?.corners?.filter { it.verdict == ProofVerdict.NotComparable || it.verdict == ProofVerdict.NotDriven }
                ?.forEach { problems += "$turn: line proof of ${it.name} ${it.verdict}" }
            // The coaching history: every focus corner measured, and known as coached.
            for (c in t.corners) {
                val a = c.attempt
                when {
                    a == null -> problems += "$turn: ${c.target.name} not measured"
                    a.verdict == FocusVerdict.NotComparable -> problems += "$turn: ${c.target.name} not comparable"
                    a.verdict == FocusVerdict.NotDriven -> problems += "$turn: ${c.target.name} has no counted pass"
                    !a.coached -> problems += "$turn: ${c.target.name} not known as coached"
                }
            }
        }
        val all = turns.flatMap { it.corners }
        val uncoachable = all.count { it.coachCorners.isEmpty() }
        // Silent where the coach worked: the plan's focus when it had one (UI-174 A), else the session focus.
        val silent = turns.sumOf { t -> t.worked.count { it.silent } }
        if (uncoachable > known.uncoachable) {
            problems += "$uncoachable focus corners without a coach corner, was ${known.uncoachable}"
        }
        if (silent > known.silent) problems += "$silent silent focus corners, was ${known.silent}"
        // 4. One target everywhere: the coach aims at the plan's lap in every corner.
        turns.flatMap { t -> t.targets.filter { it.plan == null }.map { "${t.from.label}: ${it.name}" } }
            .forEach { problems += "$it: the plan's target lap is none of the coach's laps" }
        val otherTarget = turns.sumOf { t -> t.targets.count { !it.same } }
        if (otherTarget > known.otherTarget) {
            problems += "$otherTarget corners where the coach aims at another lap than the plan, " +
                "was ${known.otherTarget}"
        }
        // 5. The coach works on the plan's focus.
        val unspoken = turns.sumOf { t -> t.proof?.corners.orEmpty().count { !it.coached } }
        if (unspoken > known.unspoken) problems += "$unspoken plan focus corners without a cue, was ${known.unspoken}"
        if (all.none { it.attempt?.verdict == FocusVerdict.Improved }) {
            problems += "no focus corner got faster all day"
        }
        assertTrue(problems.isEmpty(), "$name:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `the loop runs through Arek's six sessions at Jastrząb (29 Aug 2026)`() =
        check(
            "Jastrząb 29 Aug", RealCorpus.JASTRZAB_DAY, RealCorpus.jastrzabTrack(),
            // Silent 4 -> 6 with the plan's aims (FBBD-307): the aim is the plan's faster pass, so Corner 7
            // (10:00) and Corner 5 (13:03) now lose on 2 passes, by line or throttle, which have no cue yet.
            // Unspoken 10 -> 12 with braking points beyond his best (FBBD-312, UI-277 A): four of them join
            // the plan's focus where the driven targets left places; Corner 5 got cues in both its turns,
            // Corner 8 (14:37) and Corner 9 (16:06) none, and all four were faster in the next session.
            Known(uncoachable = 1, silent = 6, otherTarget = 15, unspoken = 12),
        )

    @Test
    fun `the loop runs through Arek's three sessions at Silesia Ring (27 Sep 2026)`() =
        check(
            "Silesia Ring 27 Sep", RealCorpus.SILESIA_DAY, RealCorpus.silesiaTrack(),
            // Silent 2 -> 4 when silent became the plan's focus (UI-174 A): Turns 6, 11 and 1 follow straights
            // of 1.6 to 2.2 s, shorter than the shortest cue (2.8 s), so nothing is said there in time.
            Known(uncoachable = 0, silent = 4, otherTarget = 6, unspoken = 5),
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
        out.appendLine()
        out.appendLine(
            "| Turn | Plan focus | Before | After | Target | Verdict | Cues | Coach aims at the plan's lap |",
        )
        out.appendLine("| --- | --- | --- | --- | --- | --- | --- | --- |")
        for (t in turns) {
            val targets = t.targets.associateBy { it.name }
            for (c in t.proof?.corners.orEmpty()) {
                out.appendLine(
                    "| ${t.from.label} → ${t.to.label} | ${c.name} | " +
                        "${fmt(c.beforeSeconds)} | ${fmt(c.afterSeconds)} | " +
                        "${fmt(c.targetSeconds)} | ${c.verdict} | ${c.cues ?: "-"} | " +
                        (targets[c.name]?.let { if (it.same) "yes" else "no" } ?: "-") + " |",
                )
            }
            val other = t.targets.count { !it.same }
            out.appendLine(
                "| ${t.from.label} → ${t.to.label} | all corners | | | | | | " +
                    "${t.targets.size - other} of ${t.targets.size} |",
            )
        }
        val text = out.toString()
        println(text)
        File("build/reports/replay-drive").apply { mkdirs() }
            .resolve("loop-${name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-")}.md")
            .writeText(text)
    }

    private fun fmt(v: Double?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    private companion object {
        const val SamePassMicros = 5_000L
        const val SameLapSeconds = 0.05
    }
}
