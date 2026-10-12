package com.flappedear.betterdriver.timing.track

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Format name in every track file, so a stray JSON file is never read as a track. */
const val TrackFileFormat = "betterdriver-track"

/**
 * The newest track file version this build reads and writes. Newer files are refused rather than
 * half read. Version 2 added point-to-point tracks (FBBD-155).
 */
const val TrackFileVersion = 2

/**
 * The version [track] is written as: 2 for a point-to-point track, which a build from before them
 * must refuse rather than read as a circuit; 1 for a circuit, so it still moves to older builds.
 */
fun trackFileVersion(track: Track): Int = if (track.pointToPoint) TrackFileVersion else 1

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * One track as a self-contained JSON file. The same file is what moves between phone and tablet
 * (FBBD-80), so it carries the format and version next to the track.
 */
object TrackJson {
    fun encode(track: Track): String {
        val body = json.encodeToJsonElement(track).jsonObject
        val wrapped = JsonObject(
            mapOf(
                "format" to json.encodeToJsonElement(TrackFileFormat),
                "version" to json.encodeToJsonElement(trackFileVersion(track)),
                "track" to body,
            ),
        )
        return json.encodeToString(JsonObject.serializer(), wrapped)
    }

    /** Throws [TrackFormatException] for anything that is not a valid track file of a known version. */
    @Suppress("ThrowsCount")
    fun decode(text: String): Track {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            throw TrackFormatException("not JSON", e)
        } catch (e: IllegalArgumentException) {
            throw TrackFormatException("not a JSON object", e)
        }
        val format = runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull()
        if (format != TrackFileFormat) throw TrackFormatException("not a track file")
        val version = runCatching { root["version"]?.jsonPrimitive?.int }.getOrNull()
            ?: throw TrackFormatException("no version")
        if (version > TrackFileVersion) throw TrackFormatException("made by a newer app (version $version)")
        val body = root["track"] ?: throw TrackFormatException("no track")
        return try {
            decodeTrack(body)
        } catch (e: TrackFormatException) {
            // A damaged corner identity (FBBD-172) never costs the track: it is made again from a lap.
            val without = (body as? JsonObject)?.takeIf { it["identity"] != null }?.let { JsonObject(it - "identity") }
                ?: throw e
            decodeTrack(without)
        }
    }

    private fun decodeTrack(body: JsonElement): Track = try {
        json.decodeFromJsonElement<Track>(body)
    } catch (e: SerializationException) {
        throw TrackFormatException("bad track: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw TrackFormatException("bad track: ${e.message}", e)
    }
}

class TrackFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
