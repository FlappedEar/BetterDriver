package com.flappedear.betterdriver.timing.circuits

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.MaxLatitude
import com.flappedear.betterdriver.timing.geo.MaxLongitude
import com.flappedear.betterdriver.timing.geo.distanceMeters
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Same format and limits as FlappedEar Telemetry's `assets/circuits/circuits.json`, which this list is ported from. */
const val CircuitListFormat = "flappedear-circuits"
const val CircuitListVersion = 1
const val DefaultCircuitRadiusMeters = 2_000.0
private const val MaxCircuitRadiusMeters = 20_000.0
private const val MaxCircuits = 20_000
private const val MaxNameChars = 128

/** Where the bundled list lives on the classpath, and on `main` in the repo for CircuitDirectory.refresh. */
const val BundledCircuitResource = "/circuits/circuits.json"
const val CircuitListUrl =
    "https://raw.githubusercontent.com/FlappedEar/BetterDriver/main/timing/src/main/resources/circuits/circuits.json"

/** Largest list text read, so a bad download cannot exhaust memory. */
const val MaxCircuitListChars = 4 * 1024 * 1024

/** A named circuit with one position and no lines (FBBD-79). Lines come from the editor. */
data class Circuit(
    val id: String,
    val name: String,
    val country: String,
    val position: GeoPoint,
    val radiusMeters: Double = DefaultCircuitRadiusMeters,
)

/** A circuit list; a higher [revision] is newer. */
data class CircuitList(val revision: Long, val source: String, val circuits: List<Circuit>) {

    /** The nearest circuit whose radius reaches [at], or null. */
    fun find(at: GeoPoint): Circuit? = circuits
        .map { it to distanceMeters(at, it.position) }
        .filter { (c, d) -> d <= c.radiusMeters }
        .minByOrNull { it.second }
        ?.first

    companion object {
        val Empty = CircuitList(0, "", emptyList())

        /**
         * Parses a list. Entries that are not valid circuits are skipped, as in FlappedEar Telemetry;
         * a file that is not a circuit list throws [CircuitListException].
         */
        @Suppress("ThrowsCount")
        fun decode(text: String): CircuitList {
            if (text.length > MaxCircuitListChars) throw CircuitListException("too large")
            val root = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                throw CircuitListException("not JSON", e)
            } catch (e: IllegalArgumentException) {
                throw CircuitListException("not a JSON object", e)
            }
            if (root.string("format") != CircuitListFormat) throw CircuitListException("not a circuit list")
            val version = root["version"]?.jsonPrimitive?.longOrNull
            if (version == null || version > CircuitListVersion) throw CircuitListException("unknown version $version")
            val revision = root["revision"]?.jsonPrimitive?.longOrNull ?: throw CircuitListException("no revision")
            val entries = runCatching { root["circuits"]?.jsonArray }.getOrNull()
                ?: throw CircuitListException("no circuits")
            if (entries.size > MaxCircuits) throw CircuitListException("too many circuits")
            return CircuitList(revision, root.string("source").orEmpty(), entries.mapNotNull(::circuitOrNull))
        }

        @Suppress("ReturnCount") // Guard clauses: each skips one kind of bad entry.
        private fun circuitOrNull(element: JsonElement): Circuit? {
            val o = element as? JsonObject ?: return null
            val id = o.string("id") ?: return null
            val name = o.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MaxNameChars } ?: return null
            val lat = o.number("lat") ?: return null
            val lon = o.number("lon") ?: return null
            val radius = o.number("radius") ?: DefaultCircuitRadiusMeters
            if (lat !in -MaxLatitude..MaxLatitude || lon !in -MaxLongitude..MaxLongitude) return null
            if (!(radius > 0 && radius <= MaxCircuitRadiusMeters)) return null
            return Circuit(id, name, o.string("country").orEmpty(), GeoPoint(lat, lon), radius)
        }

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull }.getOrNull()

        private fun JsonObject.number(key: String): Double? =
            runCatching { this[key]?.jsonPrimitive?.takeUnless { it.isString }?.doubleOrNull }.getOrNull()
                ?.takeIf { it.isFinite() }
    }
}

class CircuitListException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Outcome of CircuitDirectory.refresh (JVM). [NotSaved]: the newer list is in use but could not be kept. */
enum class CircuitRefresh { Updated, UpToDate, Failed, NotSaved }
