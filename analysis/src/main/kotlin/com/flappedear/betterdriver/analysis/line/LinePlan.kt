package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.coaching.checkedId
import com.flappedear.betterdriver.analysis.coaching.writeAtomically
import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.MetricPoint
import com.flappedear.betterdriver.analysis.geo.unprojectCoordinate
import com.flappedear.betterdriver.analysis.optimal.PedalState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import kotlin.math.abs

// The line plan (FBBD-306, Confluence "Getting faster loop"): what the line analysis found at a
// track, kept so every part of the loop says the same. The Map tab shows it, the coach says it on
// track, the 3D export draws it and the next session measures against it. Only the line analysis
// makes it; the others read it and never work out targets of their own.

/** One point of a line, where it is on the earth and on the lap. */
@Serializable
data class PlanPoint(
    val progressMeters: Double,
    val latitude: Double,
    val longitude: Double,
    /** From the analysed session's axis, positive left of travel. */
    val offsetMeters: Double? = null,
    val speedKmh: Double? = null,
    val pedal: PedalState? = null,
)

/** Where something happens on a pass: braking starts, the slowest point, back on throttle. */
@Serializable
data class PlanMark(
    val progressMeters: Double,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Double? = null,
)

/** One lap through one corner. */
@Serializable
data class PlanPass(
    /** The session's id, and what the screen calls it (empty for the analysed session). */
    val sessionId: String,
    val sessionLabel: String,
    val lapNumber: Int,
    val lapSeconds: Double,
    /** Through the corner's window. */
    val seconds: Double,
    val brake: PlanMark? = null,
    val apex: PlanMark? = null,
    val throttle: PlanMark? = null,
    val line: List<PlanPoint> = emptyList(),
)

/** One corner of the plan; [cornerId] is the track's CornerId (FBBD-172), null when the analysis had none. */
@Serializable
data class PlanCorner(
    val cornerId: String?,
    val name: String,
    val turnsLeft: Boolean,
    val startMeters: Double,
    val endMeters: Double,
    /** Time to find here: the analysed lap against the target, 0 when nothing beyond noise. */
    val gainSeconds: Double,
    val reference: PlanPass,
    val target: PlanPass,
    val advice: PlanAdvice,
    /** The road the laps used, left and right edge, where enough laps went. */
    val roadLeft: List<PlanPoint> = emptyList(),
    val roadRight: List<PlanPoint> = emptyList(),
    /** The session's fastest pass through the corner from any of its laps: what the next one is measured against. */
    val sessionBestSeconds: Double? = null,
)

/** [CornerAdvice] as kept: along-track metres target minus reference (positive later), tighter positive. */
@Serializable
data class PlanAdvice(
    val brakeMeters: Double? = null,
    val apexMeters: Double? = null,
    val tighterMeters: Double? = null,
    val minimumSpeedKmh: Double? = null,
    val pickupMeters: Double? = null,
) {
    val any: Boolean
        get() = listOf(brakeMeters, apexMeters, tighterMeters, minimumSpeedKmh, pickupMeters).any { it != null }
}

/**
 * The line plan of a track, from the latest analysed session there: its fastest lap against the
 * target through each corner (the second fastest pass over every session at the track, FBBD-177).
 */
@Serializable
data class LinePlan(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    /** The session it was made from, and when that session started: a later session's plan replaces it. */
    val sessionId: String,
    val sessionStartUtcMillis: Long,
    /** The track's corner identity revision the corner ids are in (FBBD-172); null without one. */
    val identityRevision: Int? = null,
    val referenceLapNumber: Int,
    val referenceLapSeconds: Double,
    val laps: Int,
    val sessions: Int,
    /** In track order. */
    val corners: List<PlanCorner>,
) {
    /** Most time to find first. */
    val ranked: List<PlanCorner> get() = corners.sortedByDescending { it.gainSeconds }

    val totalGainSeconds: Double get() = corners.sumOf { it.gainSeconds }

    /**
     * What to work on next (FBBD-20, FBBD-310): the corners with time to find and something to do
     * differently, most time first, at most [count]. The coach, the summary and the proof in the
     * next session all take the focus from here.
     */
    fun focus(count: Int = FocusCorners): List<PlanCorner> =
        ranked.filter { it.gainSeconds > 0.0 && it.advice.any }.take(count)

    /**
     * The perfect lap: the target through every corner one after the other. The corners' windows
     * tile the lap, so the pieces join end to end and the time is the lap's less every gain.
     */
    fun perfectLap(): PerfectLap {
        val points = mutableListOf<PlanPoint>()
        val pieces = corners.map { c ->
            val line = c.target.line.dropWhile { p -> points.lastOrNull()?.let { same(it, p) } == true }
            val from = points.size
            points += line
            PerfectPiece(
                c.cornerId, c.name, c.target.sessionId, c.target.sessionLabel, c.target.lapNumber,
                from, points.size, c.target.brake, c.target.apex, c.target.throttle,
            )
        }
        return PerfectLap(referenceLapSeconds - totalGainSeconds, points, pieces)
    }

    companion object {
        const val Format = "betterdriver-line-plan"
        const val Version = 1

        /** The coach works on at most this many corners in a session, as the session focus (FBBD-20). */
        const val FocusCorners = 3
    }
}

private fun same(a: PlanPoint, b: PlanPoint) = abs(a.progressMeters - b.progressMeters) < SameProgressMeters

private const val SameProgressMeters = 0.01

/** The perfect lap as one line, and which pass each stretch of it comes from. */
data class PerfectLap(val seconds: Double, val points: List<PlanPoint>, val pieces: List<PerfectPiece>)

/** One corner's stretch of the perfect lap: [points] from [fromIndex] to before [toIndex]. */
data class PerfectPiece(
    val cornerId: String?,
    val name: String,
    val sessionId: String,
    val sessionLabel: String,
    val lapNumber: Int,
    val fromIndex: Int,
    val toIndex: Int,
    val brake: PlanMark?,
    val apex: PlanMark?,
    val throttle: PlanMark?,
)

/**
 * [line] as the plan of [trackId], from session [sessionId] started at [startUtcMillis]; positions
 * from [origin], the analysed session's axis origin. [identityRevision] only when the analysis's
 * corners are the track's (SessionAnalysis.identified), so the corner ids are CornerIds.
 */
@Suppress("LongParameterList") // What the plan says where it came from.
fun linePlan(
    line: LineAnalysis,
    origin: GeoCoordinate,
    trackId: String,
    sessionId: String,
    startUtcMillis: Long,
    identityRevision: Int?,
): LinePlan {
    fun point(p: LinePoint): PlanPoint {
        val at = unprojectCoordinate(p.eastMeters, p.northMeters, origin)
        return PlanPoint(p.progressMeters, at.latitudeDegrees, at.longitudeDegrees, p.offsetMeters, p.speedKmh, p.pedal)
    }
    fun mark(pass: LinePass, progress: Double?, speed: Double? = null): PlanMark? {
        val near = progress?.let { p -> pass.line.minByOrNull { abs(it.progressMeters - p) } } ?: return null
        val at = unprojectCoordinate(near.eastMeters, near.northMeters, origin)
        return PlanMark(progress!!, at.latitudeDegrees, at.longitudeDegrees, speed ?: near.speedKmh)
    }
    fun pass(p: LinePass) = PlanPass(
        sessionId = p.sourceId.ifEmpty { sessionId },
        sessionLabel = p.source,
        lapNumber = p.lapNumber,
        lapSeconds = p.lapSeconds,
        seconds = p.seconds,
        brake = mark(p, p.brakingPointMeters),
        apex = mark(p, p.minimumSpeedMeters, p.minimumSpeedKmh),
        throttle = mark(p, p.pickupMeters),
        line = p.line.map(::point),
    )
    fun edge(meters: Double, at: MetricPoint, progress: Double): PlanPoint {
        val g = unprojectCoordinate(at.eastMeters, at.northMeters, origin)
        return PlanPoint(progress, g.latitudeDegrees, g.longitudeDegrees, meters)
    }
    return LinePlan(
        trackId = trackId,
        sessionId = sessionId,
        sessionStartUtcMillis = startUtcMillis,
        identityRevision = identityRevision,
        referenceLapNumber = line.referenceLapNumber,
        referenceLapSeconds = line.referenceLapSeconds,
        laps = line.laps,
        sessions = line.sessions,
        corners = line.corners.map { c ->
            val a = c.advice
            PlanCorner(
                cornerId = c.segmentId.takeIf { identityRevision != null },
                name = c.name,
                turnsLeft = c.turnsLeft,
                startMeters = c.startMeters,
                endMeters = c.endMeters,
                gainSeconds = c.gainSeconds,
                reference = pass(c.reference),
                target = pass(c.target),
                advice = PlanAdvice(a.brakeMeters, a.apexMeters, a.tighterMeters, a.minimumSpeedKmh, a.pickupMeters),
                roadLeft = c.road.map { edge(it.leftMeters, it.left, it.progressMeters) },
                roadRight = c.road.map { edge(it.rightMeters, it.right, it.progressMeters) },
                sessionBestSeconds = c.sessionBestSeconds,
            )
        },
    )
}

/**
 * The line plans of each track, one `<track id>/<session id>.json` in [dir] per analysed session,
 * kept with the coach's files so a backup carries them (FBBD-156). The track's plan is its latest
 * session's ([get]); the earlier ones are what the next session is measured against (FBBD-310).
 * Writes are synced and renamed into place; a file that cannot be read counts as none. They hold
 * positions, so they go with their track.
 */
class LinePlanStore(private val dir: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The plan of [trackId]'s latest analysed session, or null without one. */
    fun get(trackId: String): LinePlan? = plans(trackId).lastOrNull()

    /** Every session's plan at [trackId], in the order they were driven. */
    fun plans(trackId: String): List<LinePlan> =
        File(dir, checkedId(trackId)).listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
            .mapNotNull { f -> read(f)?.takeIf { it.trackId == trackId && f.name == it.sessionId + Suffix } }
            .sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))

    /** Saves [plan] as its session's unless the same is saved already; returns whether it wrote. */
    fun save(plan: LinePlan): Boolean {
        val f = fileFor(plan.trackId, plan.sessionId)
        if (read(f) == plan) return false
        writeAtomically(f, json.encodeToString(LinePlan.serializer(), plan))
        return true
    }

    /** Deletes every plan of [trackId], with its track; returns whether none is left. */
    fun delete(trackId: String): Boolean {
        val d = File(dir, checkedId(trackId))
        d.deleteRecursively()
        return !d.exists()
    }

    @Suppress("SwallowedException") // A file that cannot be read is no plan; the next analysis writes it again.
    private fun read(f: File): LinePlan? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(LinePlan.serializer(), f.readText())
                .takeIf { it.format == LinePlan.Format && it.version == LinePlan.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(trackId: String, sessionId: String) =
        File(File(dir, checkedId(trackId)), checkedId(sessionId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}
