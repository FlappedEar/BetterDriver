package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.timing.edit.LapPath
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.edit.line
import com.flappedear.betterdriver.timing.edit.lineRefs
import com.flappedear.betterdriver.timing.edit.sectorsInLapOrder
import com.flappedear.betterdriver.timing.edit.withLine
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine

/** How a tap places the selected line (UI-24), as on Android. The editor opens with [FromLap] when there is a lap. */
enum class PlaceMethod { FromLap, TwoTaps }

/** A recorded session at the track: its title and positions, newest first, for placing lines and re-timing. */
data class EditorSession(val title: String, val fixes: List<Fix>)

/** One line of the track as the editor lists and draws it (UI-23). [placed] when it is placed. */
data class EditorLine(
    /** Its place in [TrackEditor.lines], which [TrackEditor.select] takes. */
    val index: Int,
    val label: String,
    val mapLabel: String,
    val line: TrackLine?,
    val pit: Boolean,
    val selected: Boolean,
) {
    val placed: Boolean get() = line != null
}

/**
 * The iOS track editor's working copy (FBBD-336), the line editing of the Android TrackEditorState
 * (FBBD-56, FBBD-155) without Compose: pick a line ([select]), place it by tapping the map ([onTap]),
 * drag its ends ([moveEnd]), then [done]. Nothing is stored until the caller saves [track]; [changed]
 * says whether there is anything to save. The views read it again after each call.
 *
 * Not here yet: the corrections of the track's corners (FBBD-179), which the Android editor offers.
 */
@Suppress("TooManyFunctions") // One function per editor action.
class TrackEditor(initial: Track, val sessions: List<EditorSession>, stored: Track? = initial) {
    /** The newest session's drive, the "lap" lines are placed on. */
    val path: LapPath? = sessions.firstOrNull()?.fixes
        ?.map { GeoPoint(it.lat, it.lon) }
        ?.takeIf { it.size >= 2 }
        ?.let(::LapPath)

    /** The drive as a flat list for the map, [lat0, lon0, lat1, lon1, …]; empty without a session. */
    val pathPoints: List<Double> = path?.points?.flatMap { listOf(it.lat, it.lon) }.orEmpty()

    var track: Track = initial
        private set

    private var saved: Track? = stored

    /** A saved track: one never saved has nothing to delete. */
    val isStored: Boolean get() = saved != null

    val changed: Boolean get() = track != saved

    /** The line being placed, or null when the list is showing. */
    var selected: LineRef? = null
        private set

    var method: PlaceMethod = if (path != null) PlaceMethod.FromLap else PlaceMethod.TwoTaps

    /** With [PlaceMethod.TwoTaps]: the first edge, once tapped. */
    var firstTap: GeoPoint? = null
        private set

    /** The track as it was when the selected line was picked, for [cancel]. */
    private var beforePlacing: Track? = null

    val pointToPoint: Boolean get() = track.pointToPoint

    /** The selected line's current position, or null if it is not placed yet. */
    val selectedLine: TrackLine? get() = selected?.let { track.line(it) }

    /** What the bar says: the line being placed, else the track's name. */
    val title: String get() = selected?.label(pointToPoint) ?: track.displayName

    /** Every line of the track's kind, in the editor's order. */
    fun lines(): List<EditorLine> = track.lineRefs().mapIndexed { i, ref ->
        EditorLine(
            index = i,
            label = ref.label(pointToPoint),
            mapLabel = ref.mapLabel(pointToPoint),
            line = track.line(ref),
            pit = ref == LineRef.PitEntry || ref == LineRef.PitExit,
            selected = ref == selected,
        )
    }

    /** Starts placing line [index] of [lines]. */
    fun select(index: Int) {
        track.lineRefs().getOrNull(index)?.let(::select)
    }

    private fun select(ref: LineRef) {
        beforePlacing = track
        selected = ref
        firstTap = null
    }

    /** Starts placing a new sector line, after the existing ones. */
    fun addSector() {
        if (canAddSector) select(LineRef.Sector(track.sectorLines.size))
    }

    val canAddSector: Boolean get() = track.sectorLines.size < Track.MaxSectorLines

    fun onTap(lat: Double, lon: Double) {
        val ref = selected ?: return
        val at = GeoPoint(lat, lon)
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

    /** Picks how taps place the line; From your lap only with a recorded drive. */
    fun pick(m: PlaceMethod) {
        if (m == PlaceMethod.TwoTaps || path != null) {
            method = m
            firstTap = null
        }
    }

    /** Moves one end of the selected line: [left] is the end on the driver's left. */
    fun moveEnd(left: Boolean, lat: Double, lon: Double) {
        val ref = selected ?: return
        val l = track.line(ref) ?: return
        val to = GeoPoint(lat, lon)
        track = track.withLine(ref, if (left) l.copy(left = to) else l.copy(right = to))
    }

    /** Turns the selected line round, for the other direction of travel. */
    fun flip() {
        val ref = selected ?: return
        val l = track.line(ref) ?: return
        track = track.withLine(ref, l.flipped())
    }

    /** Renames the track; a blank or too long name is ignored. */
    fun rename(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n.length <= Track.MaxNameChars) track = track.copy(name = n)
    }

    /** Makes the track a circuit or point to point (FBBD-155); lines are kept either way. */
    fun setPointToPoint(on: Boolean) {
        if (selected == null) track = track.copy(kind = if (on) TrackKind.PointToPoint else TrackKind.Circuit)
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

    /** After a save of [sent]: [stored] is the track as stored. Edits made while it was saving are kept. */
    fun saved(sent: Track, stored: Track) {
        if (track == sent) track = stored
        saved = stored
    }

    /** The hint over the map while a line is placed (as Android's PlacingHint); null otherwise. */
    val hint: String?
        get() = when {
            selected == null -> null
            method == PlaceMethod.FromLap && selectedLine == null ->
                "Tap your lap (blue) where the line goes. It is placed square to the way you drove, 30 m wide."
            method == PlaceMethod.FromLap -> "Drag the ends to the track edges. Tap the lap again to move the line."
            firstTap == null -> "Tap one edge of the track."
            else -> "Now tap the other edge."
        }

    /** The note under the lines: what saving does to the sessions here (Android's RetimeNote). */
    fun retimeNote(): String {
        val lap = if (pointToPoint) "run" else "lap"
        val laps = if (track.timeable) sessions.sumOf { timeSession(track, it.fixes.asSequence()).laps.size } else 0
        return when (sessions.size) {
            0 -> "No sessions here yet. ${lap.replaceFirstChar(Char::uppercaseChar)}s are timed with these lines " +
                "from the next session."
            1 -> "Saved sessions here are re-timed when you save: 1 session, ${plural(laps, lap)}."
            else -> "Saved sessions here are re-timed when you save: ${sessions.size} sessions, ${plural(laps, lap)}."
        }
    }

    /** The text under Circuit / Point to point, or null on a circuit. */
    val kindNote: String?
        get() = when {
            !pointToPoint -> null
            track.finish == null ->
                "Runs go from the start line to the finish line. Place both to time runs; the drive back is not timed."
            else -> "Runs go from the start line to the finish line; the drive back is not timed."
        }

    private fun close() {
        selected = null
        firstTap = null
        beforePlacing = null
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

/** The editor's name for a line (UI-23); a point-to-point track's start/finish line is its start. */
private fun LineRef.label(pointToPoint: Boolean): String = when (this) {
    LineRef.StartFinish -> if (pointToPoint) "Start" else "Start / finish"
    is LineRef.Sector -> "Sector line ${index + 1}"
    LineRef.PitEntry -> "Pit entry"
    LineRef.PitExit -> "Pit exit"
    LineRef.Finish -> "Finish"
}

/** The short name drawn next to a line on the map. */
private fun LineRef.mapLabel(pointToPoint: Boolean): String = when (this) {
    LineRef.StartFinish -> if (pointToPoint) "Start" else "S/F"
    is LineRef.Sector -> "S${index + 1}"
    LineRef.PitEntry -> "Pit in"
    LineRef.PitExit -> "Pit out"
    LineRef.Finish -> "Finish"
}
