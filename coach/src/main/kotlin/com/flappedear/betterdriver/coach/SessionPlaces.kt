package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Hex characters of a [tracksKey]: plenty to tell sets of tracks apart. */
private const val KeyChars = 16

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
