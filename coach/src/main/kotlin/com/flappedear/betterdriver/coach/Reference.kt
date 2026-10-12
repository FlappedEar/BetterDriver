package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.track.Track
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.roundToLong

/**
 * What the coach knows about a track from earlier sessions (FBBD-26, FBBD-96): the fastest timed lap's
 * path and corners, and for each corner the fastest pass over every timed lap, so a cue only ever asks
 * for something the driver has already done there. Positions are in the lap timer's frame for the
 * track revision it was built with; a track whose lines changed gets a new reference.
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
    /** The fastest pass of each corner, in corner order; a corner with no complete pass is left out. */
    val targets: List<CornerPass>,
    /** Sessions it was built from. */
    val sessions: List<String>,
) {
    fun path() = LapTrace(xs.indices.map { Xy(xs[it], ys[it]) }, timesMicros, speedsKmh)

    fun map() = CornerMap(path(), corners)

    /** Built for this revision of [track]'s lines. */
    fun fits(track: Track) = track.id == trackId && track.revision == trackRevision

    companion object {
        const val Format = "betterdriver-coach-reference"
        const val Version = 1
    }
}

/** A session's laps at one track, with the session's id. */
class SessionLaps(val sessionId: String, val laps: List<TracedLap>)

private const val CentiUnits = 100.0

private fun Double.centi() = (this * CentiUnits).roundToLong() / CentiUnits

/**
 * The reference for [track] from [sessions] (FBBD-96), or null without a timed lap. The fastest timed
 * lap gives the path and corners; every timed lap's passes are measured against it and the fastest
 * pass of each corner becomes its target.
 */
fun buildReference(track: Track, sessions: List<SessionLaps>): CoachReference? {
    val timed = sessions.flatMap { s -> s.laps.filter { it.lap.kind == LapKind.Timed }.map { s.sessionId to it } }
    val (bestSession, best) = timed.minByOrNull { it.second.lap.timeMicros } ?: return null
    val corners = findCorners(best.trace)
    val map = CornerMap(best.trace, corners)
    val targets = bestPasses(timed.flatMap { (_, l) -> map.passes(l.trace, l.lap.number) })
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
    )
}

/** The fastest pass of each corner, in corner order. */
fun bestPasses(passes: List<CornerPass>): List<CornerPass> =
    passes.groupBy { it.corner }.values.map { p -> p.minBy { it.timeMicros } }.sortedBy { it.corner }

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

    private fun fileFor(trackId: String): File = File(dir, "${checkedTrackId(trackId)}.coach.json")
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
