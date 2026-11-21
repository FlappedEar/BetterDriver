package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the coach knows about a track from earlier sessions (FBBD-26, FBBD-96): the fastest timed lap's
 * path and corners, and for each corner the two fastest passes over every timed lap, so a cue only
 * ever asks for something the driver has already done there, twice (FBBD-177). Positions are in the
 * lap timer's frame for the track revision it was built with; a track whose lines changed gets a new
 * reference.
 */
@kotlinx.serialization.Serializable
data class CoachReference(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val trackRevision: Int,
    /** The fastest lap: its session and number, and its time. */
    val lapSession: String,
    val lapNumber: Int,
    val lapMicros: Long,
    val xs: List<Double>,
    val ys: List<Double>,
    val timesMicros: List<Long>,
    val speedsKmh: List<Double>,
    val corners: List<Corner>,
    /**
     * The two fastest passes of each corner (FBBD-177: a cue aims at a time driven twice), in corner
     * order, fastest first; passes too weak to coach from and corners with no complete pass are left out.
     */
    val targets: List<CornerPass>,
    /** Sessions it was built from. */
    val sessions: List<String>,
    /** The line plan the [aims] come from: the session it was made from (FBBD-306); null without one. */
    val planSession: String? = null,
    /** That plan's [PlannedTargets.key], so a plan rewritten for the same session rebuilds the reference. */
    val planKey: String? = null,
    /**
     * Per corner, the pass the line plan aims at there (FBBD-307): the plan's target lap measured by
     * the coach's own meter, so the coach asks for what the Map tab shows. Corners without one aim at
     * the second fastest of [targets].
     */
    val aims: List<CornerPass> = emptyList(),
    /** The car's gears from the sessions (FBBD-326), to read the gear of a pass; null without engine speed. */
    val gearbox: Gearbox? = null,
) {
    fun path() = LapTrace(xs.indices.map { Xy(xs[it], ys[it]) }, timesMicros, speedsKmh)

    fun map() = CornerMap(path(), corners)

    /** Built for this revision of [track]'s lines. */
    fun fits(track: Track) = track.id == trackId && track.revision == trackRevision

    companion object {
        const val Format = "betterdriver-coach-reference"
        /**
         * 2: two targets per corner (FBBD-177). 3: passes carry their line at the apex and their
         * throttle point (FBBD-307). 4: the line plan's aims (FBBD-307). 5: passes carry their time to
         * the apex (UI-170 B). 6: aims carry the plan's gear and the reference the gearbox (FBBD-326).
         * 7: a plan lap that two laps match in time gives no aim (FBBD-405). 8: the plan's lap is found
         * by its start where the plan has it (F5). 9: a corner in every track corner (FBBD-405). An older
         * file is rebuilt from the sessions.
         */
        const val Version = 9
    }
}

/**
 * A session's laps at one track, with the session's id. [originMicros]: the session clock at its first
 * sample, where the analysis's telemetry time is 0, so a line plan's lap is found by its start (F5).
 */
class SessionLaps(val sessionId: String, val laps: List<TracedLap>, val originMicros: Long? = null)

/** [data]'s laps at [track], with its first sample's time. */
fun sessionLaps(track: Track, data: SessionData): SessionLaps =
    SessionLaps(data.id, tracedLaps(track, data.coachFixes()), data.sampleSets.minOfOrNull { it.tMicros })

/** The corner [corner]'s aim: the line plan's pass there, or the second fastest pass (FBBD-177). */
fun CoachReference.aim(corner: Int): CornerPass? =
    aims.firstOrNull { it.corner == corner } ?: targets.filter { it.corner == corner }.getOrNull(ConfirmingPasses - 1)

/**
 * In the line plan of [cornerId], the target lap: session [sessionId]'s lap timed at [lapSeconds] (FBBD-306).
 * Where the plan has a braking point beyond his best (FBBD-312), the aim brakes [brakeLaterMeters]
 * later than that lap did and is [brakeLaterSeconds] faster, so the coach asks for the step.
 * [gear] is the plan's gear at the slowest point (FBBD-326), asked for with the brake cue.
 * [lapStartSeconds]: when that lap started, seconds from the session's first sample (F5); null in a
 * plan saved before it, which is then found by its time alone. [driven]: for a braking point beyond
 * his best, the corner's target driven twice without the step (F4), which [drivenOnly] aims at.
 */
@Suppress("LongParameterList") // The plan's lap (session, time, start), its step, gear and the driven alternative.
class PlannedTarget(
    val cornerId: String,
    val sessionId: String,
    val lapSeconds: Double,
    val brakeLaterMeters: Int = 0,
    val brakeLaterSeconds: Double = 0.0,
    val gear: Int? = null,
    val lapStartSeconds: Double? = null,
    val driven: PlannedTarget? = null,
)

/** The targets of a track's line plan, made from session [sessionId] (FBBD-306). */
class PlannedTargets(val sessionId: String, val targets: List<PlannedTarget>, val gearbox: Gearbox? = null) {
    /** The same for the same session and targets. */
    val key: String
        get() = sessionId + targets.joinToString("") { t ->
            val later = if (t.brakeLaterMeters > 0) "+${t.brakeLaterMeters}" else ""
            val gear = t.gear?.let { "/$it" }.orEmpty()
            val start = t.lapStartSeconds?.let { "~$it" }.orEmpty()
            "|${t.cornerId}=${t.sessionId}@${t.lapSeconds}$start$later$gear"
        }
}

/** The analysis and the coach time a lap from the same fixes, apart by at most this. */
private const val SameLapSeconds = 0.06

private const val CentiUnits = 100.0

private fun Double.centi() = (this * CentiUnits).roundToLong() / CentiUnits

/**
 * The reference for [track] from [sessions] (FBBD-96), or null without a timed lap. The fastest timed
 * lap gives the path and corners; every timed lap's passes are measured against it and the fastest
 * two passes of each corner become its targets.
 */
fun buildReference(track: Track, sessions: List<SessionLaps>, plan: PlannedTargets? = null): CoachReference? {
    // Laps with a GNSS gap (audit A11) are left out: their time is partly interpolated.
    val timed = sessions.flatMap { s -> s.laps.filter { it.lap.countsForBest }.map { s.sessionId to it } }
    val origins = sessions.associate { it.sessionId to it.originMicros }
    val (bestSession, best) = timed.minByOrNull { it.second.lap.timeMicros } ?: return null
    val found = findCorners(best.trace)
    // Named by the track's corners (FBBD-172); the coach names them again from the track it runs at.
    // Every track corner has one (FBBD-405), so the coach measures and aims where the line plan does.
    val corners = lapFrame(track)?.let { withTrackCorners(found, best.trace, it, track.identity) } ?: found
    val map = CornerMap(best.trace, corners)
    val targets = bestPasses(timed.flatMap { (_, l) -> map.passes(l.trace, l.lap.number) }, ConfirmingPasses)
    val aims = plan?.let { aims(map, timed, origins, it) }.orEmpty()
    val t = best.trace
    return CoachReference(
        trackId = track.id,
        trackRevision = track.revision,
        lapSession = bestSession,
        lapNumber = best.lap.number,
        lapMicros = best.lap.timeMicros,
        xs = t.points.map { it.x.centi() },
        ys = t.points.map { it.y.centi() },
        timesMicros = t.timesMicros,
        speedsKmh = t.speedsKmh.map { it.centi() },
        corners = corners,
        targets = targets,
        sessions = sessions.map { it.sessionId },
        planSession = plan?.sessionId,
        planKey = plan?.key,
        aims = aims,
        gearbox = plan?.gearbox,
    )
}

/**
 * The passes [plan] aims at, measured on [map]: in every corner of a planned CornerId, the pass of
 * the planned lap, when that lap is one of [timed] and the pass is not too weak to coach from; at a
 * braking corner with a braking point beyond his best, that pass braking later ([beyond]).
 */
private fun aims(
    map: CornerMap,
    timed: List<Pair<String, TracedLap>>,
    origins: Map<String, Long?>,
    plan: PlannedTargets,
): List<CornerPass> =
    plan.targets.flatMap { p ->
        val numbers = map.corners.filter { it.cornerId == p.cornerId }.map { it.number }.toSet()
        val lap = plannedLap(timed.filter { it.first == p.sessionId }.map { it.second }, origins[p.sessionId], p)
        if (numbers.isEmpty() || lap == null) {
            emptyList()
        } else {
            map.passes(lap.trace, lap.lap.number).filter { it.corner in numbers && !it.unusable() }
                .map { pass ->
                    val braked = map.corners.getOrNull(pass.corner - 1)?.braked == true
                    (if (braked) pass.brakingLater(p.brakeLaterMeters, p.brakeLaterSeconds) else pass)
                        .copy(plannedGear = p.gear)
                }
        }
    }.distinctBy { it.corner }.sortedBy { it.corner }

/**
 * The lap of [laps] (one session's) that [p] plans from. The analysis and the coach number laps each
 * their own way, so it is the lap with the plan's time; where the plan has the lap's start and the
 * session its clock at the first sample ([originMicros]), the one that also started then (F5), and
 * none when no lap of that time did. Without a start, two laps that close in time are no identity
 * (FBBD-405): no lap, rather than a guess.
 */
fun plannedLap(laps: List<TracedLap>, originMicros: Long?, p: PlannedTarget): TracedLap? {
    val sameTime = laps.filter { abs(it.lap.timeMicros / MicrosPerSecond - p.lapSeconds) <= SameLapSeconds }
    val at = p.lapStartSeconds?.let { start -> originMicros?.plus((start * MicrosPerSecond).roundToLong()) }
    // With a start, only a lap that started then is that lap: another of the same time is another lap.
    return if (at == null) {
        sameTime.singleOrNull()
    } else {
        sameTime.filter { abs(it.lap.startMicros - at) <= SameStartMicros }.minByOrNull { abs(it.lap.startMicros - at) }
    }
}

/** The analysis and the coach place a lap's start on the same line crossing, apart by at most this. */
private const val SameStartMicros = 1_000_000L

/**
 * This pass braking [meters] later and [seconds] faster to the apex (FBBD-312): a braking point
 * beyond his best as an aim, so the coach asks for it as any brake cue. As it is without either.
 */
internal fun CornerPass.brakingLater(meters: Int, seconds: Double): CornerPass {
    if (meters <= 0 || seconds <= 0.0) return this
    val faster = (seconds * MicrosPerSecond).roundToLong()
    return copy(
        brakeAt = brakeAt + meters,
        timeMicros = timeMicros - faster,
        apexMicros = apexMicros?.minus(faster),
        plannedBrakeLaterMeters = meters,
    )
}

private const val MicrosPerSecond = 1_000_000.0

/** The [count] fastest passes of each corner that are not too weak to coach from, in corner order, fastest first. */
fun bestPasses(passes: List<CornerPass>, count: Int = 1): List<CornerPass> =
    passes.filterNot { it.unusable() }.groupBy { it.corner }.entries.sortedBy { it.key }
        .flatMap { (_, p) -> p.sortedBy { it.timeMicros }.take(count) }

/** A coach reference file's text (`<track id>.coach.json`), as CoachReferenceStore keeps it on Android. */
object CoachReferenceJson {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun encode(reference: CoachReference): String = json.encodeToString(CoachReference.serializer(), reference)

    /** The reference of [trackId] in [text]; null when it does not read as one of this format and version. */
    fun decode(text: String, trackId: String): CoachReference? = try {
        json.decodeFromString(CoachReference.serializer(), text)
            .takeIf { it.format == CoachReference.Format && it.version == CoachReference.Version }
            ?.takeIf { it.trackId == trackId }
    } catch (_: kotlinx.serialization.SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
