package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
private data class PlacesFile(
    val format: String = SessionPlaces.Format,
    val version: Int = SessionPlaces.Version,
    val sessions: Map<String, SessionPlace> = emptyMap(),
)

/**
 * [SessionPlace] of each session by id, in one JSON [file], written atomically. A file that cannot be
 * read counts as empty: every session is worked out again. Shared code (FBBD-359).
 */
class SessionPlaces(private val file: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(): MutableMap<String, SessionPlace> {
        if (!CoreFiles.isFile(file)) return HashMap()
        return try {
            json.decodeFromString(PlacesFile.serializer(), CoreFiles.readText(file))
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
