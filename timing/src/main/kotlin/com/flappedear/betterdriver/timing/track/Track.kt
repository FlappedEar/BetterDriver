package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.cos
import kotlin.math.sin

/**
 * A timing line between two points. Direction matters: a car crosses it forwards when [left] is on
 * its left and [right] on its right. Crossings the other way are ignored, so a car reversing over
 * the line, or a pit lane running the other way, never counts.
 */
@Serializable
data class TrackLine(val left: GeoPoint, val right: GeoPoint) {
    /** The same line for the opposite direction of travel. */
    fun flipped() = TrackLine(right, left)

    companion object {
        /**
         * A line [widthMeters] wide, centred on [center], square to a car heading [headingDeg]
         * (clockwise from north). Used to take a line from a recorded lap.
         */
        fun across(center: GeoPoint, headingDeg: Double, widthMeters: Double = DefaultLineWidthMeters): TrackLine {
            require(widthMeters > 0) { "line width must be positive" }
            val frame = LocalFrame(center)
            val h = Math.toRadians(headingDeg)
            // Unit vector of travel (x east, y north) and its left-hand normal.
            val forward = Xy(sin(h), cos(h))
            val leftward = Xy(-forward.y, forward.x)
            val half = widthMeters / 2
            return TrackLine(frame.toGeo(leftward * half), frame.toGeo(leftward * -half))
        }
    }
}

/** Width of a line placed by heading: wide enough for any track, narrow enough to miss a parallel pit lane. */
const val DefaultLineWidthMeters = 30.0

/** How a track is timed (FBBD-155). */
@Serializable
enum class TrackKind {
    /** Laps from the start/finish line round to it again. */
    @SerialName("circuit")
    Circuit,

    /** Runs from a start line to a separate finish line, as a hill climb or a sprint; the drive back is not timed. */
    @SerialName("pointToPoint")
    PointToPoint,
}

/** How far from its centre a saved track is recognised, by default, in metres (as FlappedEar Telemetry). */
const val DefaultTrackRadiusMeters = 2_000.0

/**
 * One of Arek's tracks (FBBD-54): a name, where it is, and its timing lines. [sectorLines] split the
 * lap in order, so a lap has `sectorLines.size + 1` sectors. [pitEntry] and [pitExit] are optional;
 * they only decide which laps are in and out laps. [circuitId] links the track to the built-in
 * directory entry it was started from (FBBD-79), if any. [revision] goes up on every edit.
 *
 * A [TrackKind.PointToPoint] track (FBBD-155) times runs from [startFinish], its start line, to
 * [finish]; everywhere else (live timing, review, analysis) a run is a lap. Pit lines do not apply
 * to it. Tracks saved before there were kinds have neither field and read as circuits.
 *
 * [identity] is the track's corners and straights (FBBD-172), with the ids every screen and the coach
 * name them by. It is not part of [revision], which counts edits of the lines: it is made from the
 * first lap analysed at the track and kept here, and tracks saved before it have none until then.
 */
@Serializable
data class Track(
    val id: String,
    val name: String,
    val center: GeoPoint,
    val country: String = "",
    val layout: String = "",
    val radiusMeters: Double = DefaultTrackRadiusMeters,
    val startFinish: TrackLine? = null,
    val sectorLines: List<TrackLine> = emptyList(),
    val pitEntry: TrackLine? = null,
    val pitExit: TrackLine? = null,
    val circuitId: String? = null,
    val revision: Int = 1,
    val updatedUtcMillis: Long = 0,
    val kind: TrackKind = TrackKind.Circuit,
    /** The finish line of a point-to-point track; unused on a circuit. */
    val finish: TrackLine? = null,
    val identity: TrackRevision? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= MaxIdChars && id.all { it.isLetterOrDigit() || it in "-_" }) {
            "track id must be 1 to $MaxIdChars letters, digits, '-' or '_'"
        }
        require(name.isNotBlank() && name.length <= MaxNameChars) { "track name must be 1 to $MaxNameChars characters" }
        require(layout.length <= MaxNameChars) { "layout must be at most $MaxNameChars characters" }
        require(radiusMeters.isFinite() && radiusMeters > 0 && radiusMeters <= MaxRadiusMeters) {
            "radius must be in (0, $MaxRadiusMeters] m"
        }
        require(sectorLines.size <= MaxSectorLines) { "at most $MaxSectorLines sector lines" }
        require(revision >= 1) { "revision starts at 1" }
    }

    /** Runs from a start line to a separate finish line rather than laps (FBBD-155). */
    val pointToPoint: Boolean get() = kind == TrackKind.PointToPoint

    /** Lines are set, so laps can be timed: the start/finish line, and on a point-to-point track the finish. */
    val timeable: Boolean get() = startFinish != null && (!pointToPoint || finish != null)

    /** Sectors per lap: 1 with no sector lines. */
    val sectorCount: Int get() = sectorLines.size + 1

    /** Name with layout, for lists: "Tor Poznań · Full". */
    val displayName: String get() = if (layout.isBlank()) name else "$name · $layout"

    companion object {
        const val MaxIdChars = 64
        const val MaxNameChars = 128
        const val MaxSectorLines = 32
        const val MaxRadiusMeters = 20_000.0
    }
}
