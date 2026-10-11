package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.flappedear.betterdriver.timing.edit.LapPath
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.edit.line
import com.flappedear.betterdriver.timing.edit.sectorsInLapOrder
import com.flappedear.betterdriver.timing.edit.withLine
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine

/** How a tap places the selected line (UI-24). The editor opens with [FromLap] when there is a lap. */
enum class PlaceMethod { FromLap, TwoTaps }

/** A recorded session at this track: its title and positions, for placing lines and re-timing. */
data class EditorSession(val title: String, val fixes: List<Fix>)

/**
 * The track editor's working copy (FBBD-56). Pick a line ([select]), place it by tapping the map
 * ([onTap]), drag its ends ([moveEnd]), then [done]. Nothing is stored until the caller saves
 * [track]; [changed] says whether there is anything to save.
 */
@Stable
class TrackEditorState(initial: Track, sessions: List<EditorSession>) {
    /** The newest session's drive, the "lap" lines are placed on. */
    val path: LapPath? = sessions.firstOrNull()?.fixes
        ?.map { GeoPoint(it.lat, it.lon) }
        ?.takeIf { it.size >= 2 }
        ?.let(::LapPath)

    var track by mutableStateOf(initial)
        private set

    private var saved by mutableStateOf(initial)

    val changed: Boolean get() = track != saved

    /** The line being placed, or null when the list is showing. */
    var selected by mutableStateOf<LineRef?>(null)
        private set

    var method by mutableStateOf(if (path != null) PlaceMethod.FromLap else PlaceMethod.TwoTaps)

    /** With [PlaceMethod.TwoTaps]: the first edge, once tapped. */
    var firstTap by mutableStateOf<GeoPoint?>(null)
        private set

    /** The track as it was when the selected line was picked, for [cancel]. */
    private var beforePlacing: Track? = null

    /** The selected line's current position, or null if it is not placed yet. */
    val selectedLine: TrackLine? get() = selected?.let { track.line(it) }

    fun select(ref: LineRef) {
        beforePlacing = track
        selected = ref
        firstTap = null
    }

    /** Starts placing a new sector line, after the existing ones. */
    fun addSector() = select(LineRef.Sector(track.sectorLines.size))

    val canAddSector: Boolean get() = track.sectorLines.size < Track.MaxSectorLines

    fun onTap(at: GeoPoint) {
        val ref = selected ?: return
        when (method) {
            PlaceMethod.FromLap -> path?.lineAt(at)?.let { track = track.withLine(ref, it) }
            PlaceMethod.TwoTaps -> {
                val first = firstTap
                if (first == null) {
                    firstTap = at
                } else {
                    val drawn = TrackLine(first, at)
                    track = track.withLine(ref, path?.oriented(drawn) ?: drawn)
                    firstTap = null
                }
            }
        }
    }

    /** Moves one end of the selected line: [left] is the end on the driver's left. */
    fun moveEnd(left: Boolean, to: GeoPoint) {
        val ref = selected ?: return
        val l = track.line(ref) ?: return
        track = track.withLine(ref, if (left) l.copy(left = to) else l.copy(right = to))
    }

    /** Turns the selected line round, for the other direction of travel. */
    fun flip() {
        val ref = selected ?: return
        val l = track.line(ref) ?: return
        track = track.withLine(ref, l.flipped())
    }

    /** Removes the selected line and goes back to the list. */
    fun remove() {
        val ref = selected ?: return
        if (track.line(ref) != null) track = track.withLine(ref, null)
        close()
    }

    /** Keeps the placed line and goes back to the list; sector lines are put back in lap order. */
    fun done() {
        val p = path
        if (selected is LineRef.Sector && p != null) track = track.sectorsInLapOrder(p)
        close()
    }

    /** Undoes everything since the line was picked and goes back to the list. */
    fun cancel() {
        beforePlacing?.let { track = it }
        close()
    }

    /**
     * After a save of [sent]: [stored] is the track as stored (next revision). Edits made while it
     * was saving are kept and still count as changes.
     */
    fun saved(sent: Track, stored: Track) {
        if (track == sent) track = stored
        saved = stored
    }

    private fun close() {
        selected = null
        firstTap = null
        beforePlacing = null
    }
}

/** The editor's name for a line (UI-23). */
fun LineRef.label(): String = when (this) {
    LineRef.StartFinish -> "Start / finish"
    is LineRef.Sector -> "Sector line ${index + 1}"
    LineRef.PitEntry -> "Pit entry"
    LineRef.PitExit -> "Pit exit"
}

/** The short name drawn next to a line on the map. */
fun LineRef.mapLabel(): String = when (this) {
    LineRef.StartFinish -> "S/F"
    is LineRef.Sector -> "S${index + 1}"
    LineRef.PitEntry -> "Pit in"
    LineRef.PitExit -> "Pit out"
}
