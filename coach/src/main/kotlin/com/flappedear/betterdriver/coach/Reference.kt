package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.gear.Gearbox
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.track.Track
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
         * An older file is rebuilt from the sessions.
         */
        const val Version = 6
    }
}

/** A session's laps at one track, with the session's id. */
class SessionLaps(val sessionId: String, val laps: List<TracedLap>)

/** The corner [corner]'s aim: the line plan's pass there, or the second fastest pass (FBBD-177). */
fun CoachReference.aim(corner: Int): CornerPass? =
    aims.firstOrNull { it.corner == corner } ?: targets.filter { it.corner == corner }.getOrNull(ConfirmingPasses - 1)

/**
 * In the line plan of [cornerId], the target lap: session [sessionId]'s lap timed at [lapSeconds] (FBBD-306).
 * Where the plan has a braking point beyond his best (FBBD-312), the aim brakes [brakeLaterMeters]
 * later than that lap did and is [brakeLaterSeconds] faster, so the coach asks for the step.
 * [gear] is the plan's gear at the slowest point (FBBD-326), asked for with the brake cue.
 */
class PlannedTarget(
    val cornerId: String,
    val sessionId: String,
    val lapSeconds: Double,
    val brakeLaterMeters: Int = 0,
    val brakeLaterSeconds: Double = 0.0,
    val gear: Int? = null,
)

/** The targets of a track's line plan, made from session [sessionId] (FBBD-306). */
class PlannedTargets(val sessionId: String, val targets: List<PlannedTarget>, val gearbox: Gearbox? = null) {
    /** The same for the same session and targets. */
    val key: String
        get() = sessionId + targets.joinToString("") { t ->
            val later = if (t.brakeLaterMeters > 0) "+${t.brakeLaterMeters}" else ""
            val gear = t.gear?.let { "/$it" }.orEmpty()
            "|${t.cornerId}=${t.sessionId}@${t.lapSeconds}$later$gear"
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
    val (bestSession, best) = timed.minByOrNull { it.second.lap.timeMicros } ?: return null
    val found = findCorners(best.trace)
    // Named by the track's corners (FBBD-172); the coach names them again from the track it runs at.
    val corners = lapFrame(track)?.let { identifyCorners(found, best.trace, it, track.identity) } ?: found
    val map = CornerMap(best.trace, corners)
    val targets = bestPasses(timed.flatMap { (_, l) -> map.passes(l.trace, l.lap.number) }, ConfirmingPasses)
    val aims = plan?.let { aims(map, timed, it) }.orEmpty()
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
private fun aims(map: CornerMap, timed: List<Pair<String, TracedLap>>, plan: PlannedTargets): List<CornerPass> =
    plan.targets.flatMap { p ->
        val numbers = map.corners.filter { it.cornerId == p.cornerId }.map { it.number }.toSet()
        val lap = timed.filter { it.first == p.sessionId }
            .map { it.second }
            .filter { abs(it.lap.timeMicros / MicrosPerSecond - p.lapSeconds) <= SameLapSeconds }
            .minByOrNull { abs(it.lap.timeMicros / MicrosPerSecond - p.lapSeconds) }
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
    passes.filterNot { it.unusable() }.groupBy { it.corner }.toSortedMap().values
        .flatMap { p -> p.sortedBy { it.timeMicros }.take(count) }

/**
 * One reference file per track in [dir] (`<track id>.coach.json`). Writes are synced and renamed into
 * place. A file that cannot be read counts as no reference; it is rebuilt from the sessions.
 */
class CoachReferenceStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(trackId: String): CoachReference? {
        val f = fileFor(trackId)
        if (!f.isFile) return null
        return try {
            json.decodeFromString(CoachReference.serializer(), f.readText())
                .takeIf { it.format == CoachReference.Format && it.version == CoachReference.Version }
                ?.takeIf { it.trackId == trackId }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun save(reference: CoachReference) {
        writeAtomically(fileFor(reference.trackId), json.encodeToString(CoachReference.serializer(), reference))
    }

    fun delete(trackId: String) = fileFor(trackId).delete()

    /** Ids of the tracks with a reference file, readable or not. */
    fun trackIds(): List<String> = dir.listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
        .map { it.name.removeSuffix(Suffix) }
        .filter { id -> runCatching { checkedTrackId(id) }.isSuccess }

    private fun fileFor(trackId: String): File = File(dir, "${checkedTrackId(trackId)}$Suffix")

    private companion object {
        const val Suffix = ".coach.json"
    }
}

/** [trackId], once it is known to be safe as a file name. */
internal fun checkedTrackId(trackId: String): String {
    require(trackId.isNotBlank() && trackId.all { it.isLetterOrDigit() || it in "-_" }) { "bad track id: $trackId" }
    return trackId
}

/** Writes [text] to a temporary file, syncs it and renames it over [target], so a reader never sees half of it. */
internal fun writeAtomically(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    FileOutputStream(tmp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    try {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
