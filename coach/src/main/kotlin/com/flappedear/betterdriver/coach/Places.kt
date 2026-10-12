package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Slack on [Area.reaches], for the flat-earth nearest point and the rounding of an area's edges. */
private const val AreaMarginMeters = 100.0

/** Hex characters of a [tracksKey]: plenty to tell sets of tracks apart. */
private const val KeyChars = 16

/** The box of latitudes and longitudes a session's positions fall in (FBBD-168). */
@Serializable
data class Area(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** Whether [track]'s radius reaches into the box, so a position in it may be at the track. */
    fun reaches(track: Track): Boolean {
        val c = track.center
        val nearest = GeoPoint(c.lat.coerceIn(south, north), c.lon.coerceIn(west, east))
        return distanceMeters(c, nearest) <= track.radiusMeters + AreaMarginMeters
    }

    companion object {
        /** The box of [fixes]; null without a usable position. */
        fun of(fixes: Sequence<CoachFix>): Area? {
            var s = Double.POSITIVE_INFINITY
            var w = Double.POSITIVE_INFINITY
            var n = Double.NEGATIVE_INFINITY
            var e = Double.NEGATIVE_INFINITY
            for (f in fixes) {
                if (runCatching { GeoPoint(f.lat, f.lon) }.isFailure) continue
                s = minOf(s, f.lat)
                w = minOf(w, f.lon)
                n = maxOf(n, f.lat)
                e = maxOf(e, f.lon)
            }
            return if (s.isFinite()) Area(s, w, n, e) else null
        }
    }
}

/**
 * What the coach library knows about one session (FBBD-168). [stamp] says which state of the session
 * it was worked out from. [recorded] is the saved track its track event names, if any. Sessions
 * matched by position have their samples [read] once for their [area] (null: no position); [matched]
 * is the track they time laps on among the tracks near that area, which [against] identifies, so it
 * is worked out again only when one of those tracks is saved, edited or deleted.
 */
@Serializable
data class SessionPlace(
    val stamp: String,
    val recorded: String? = null,
    val read: Boolean = false,
    val area: Area? = null,
    val against: String? = null,
    val matched: String? = null,
)

/**
 * A key for [tracks] as they are now: the same tracks with the same lines, radius and revision give
 * the same key, and any save, edit or delete among them gives another.
 */
fun tracksKey(tracks: List<Track>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    for (t in tracks.sortedBy { it.id }) {
        digest.update(TrackJson.encode(t).toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(KeyChars)
}

@Serializable
private data class PlacesFile(
    val format: String = SessionPlaces.Format,
    val version: Int = SessionPlaces.Version,
    val sessions: Map<String, SessionPlace> = emptyMap(),
)

/**
 * [SessionPlace] of each session by id, in one JSON [file], written atomically. A file that cannot be
 * read counts as empty: every session is worked out again.
 */
class SessionPlaces(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): MutableMap<String, SessionPlace> {
        if (!file.isFile) return HashMap()
        return try {
            json.decodeFromString(PlacesFile.serializer(), file.readText())
                .takeIf { it.format == Format && it.version == Version }
                ?.sessions?.toMutableMap()
                ?: HashMap()
        } catch (_: SerializationException) {
            HashMap()
        } catch (_: IllegalArgumentException) {
            HashMap()
        } catch (_: IOException) {
            HashMap()
        }
    }

    fun write(places: Map<String, SessionPlace>) {
        writeAtomically(file, json.encodeToString(PlacesFile.serializer(), PlacesFile(sessions = places)))
    }

    companion object {
        const val Format = "betterdriver-coach-places"
        const val Version = 1
    }
}
