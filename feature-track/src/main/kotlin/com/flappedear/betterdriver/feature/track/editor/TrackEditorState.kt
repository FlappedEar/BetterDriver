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
import com.flappedear.betterdriver.timing.track.MinCorrectedSegmentMeters
import com.flappedear.betterdriver.timing.track.RevisionSegment
import com.flappedear.betterdriver.timing.track.SegmentEdit
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackKind
import com.flappedear.betterdriver.timing.track.TrackLine
import com.flappedear.betterdriver.timing.track.corrected

/** How a tap places the selected line (UI-24). The editor opens with [FromLap] when there is a lap. */
enum class PlaceMethod { FromLap, TwoTaps }

/** A recorded session at this track: its title and positions, for placing lines and re-timing. */
data class EditorSession(val title: String, val fixes: List<Fix>)

/**
 * The track editor's working copy (FBBD-56). Pick a line ([select]), place it by tapping the map
 * ([onTap]), drag its ends ([moveEnd]), then [done]. Nothing is stored until the caller saves
 * [track]; [changed] says whether there is anything to save.
 *
 * [stored] is the track as saved, if it is; a track proposed [fromSession] (FBBD-151) opens with
 * lines that are not saved yet, so it starts changed and leaving asks first.
 *
 * The track's corners and straights ([identity], FBBD-179) are corrected here too: pick one
 * ([selectSegment], or a tap on the map), then rename, retype, split, merge or remove it, or drag its
 * ends along the track ([dragBoundary], [endDrag]). A correction is not part of [track]: the caller
 * stores each one as it is made ([correctionStored]), so it never waits for Save.
 */
@Stable
@Suppress("TooManyFunctions") // One function per editor action.
class TrackEditorState(
    initial: Track,
    sessions: List<EditorSession>,
    stored: Track? = initial,
    fromSession: Boolean = false,
) {
    /** The newest session's drive, the "lap" lines are placed on. */
    val path: LapPath? = sessions.firstOrNull()?.fixes
        ?.map { GeoPoint(it.lat, it.lon) }
        ?.takeIf { it.size >= 2 }
        ?.let(::LapPath)

    var track by mutableStateOf(initial)
        private set

    private var saved by mutableStateOf(stored)

    val changed: Boolean get() = track != saved

    /** The lines are still the ones proposed from a session, not saved yet (FBBD-151). */
    var proposed by mutableStateOf(fromSession)
        private set

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

    /** The track's corners and straights as stored (FBBD-172, FBBD-179); null before any analysis there. */
    var identity by mutableStateOf(stored?.identity ?: initial.identity)
        private set

    /** The centreline's length, for the corrections' arithmetic. */
    private var identityLength = identity?.lengthMeters ?: 0.0

    /** The corner or straight being corrected, by id, or null. */
    var segmentId by mutableStateOf<String?>(null)
        private set

    /** The corner or straight being corrected. */
    val selectedSegment: RevisionSegment? get() = segmentId?.let { identity?.segment(it) }

    /** While an end of [selectedSegment] is dragged: which ([SegmentEnd]) and where, in metres along the track. */
    var dragged by mutableStateOf<Pair<SegmentEnd, Double>?>(null)
        private set

    /** A correction is being stored; nothing else is offered until it is. */
    var correcting by mutableStateOf(false)
        private set

    /** What the last correction left to say: why it was not kept. */
    var correctionNote by mutableStateOf<String?>(null)
        private set

    /** The track as stored with its corners as corrected here, for sharing (FBBD-179). */
    val shared: Track get() = track.copy(identity = identity)

    fun select(ref: LineRef) {
        beforePlacing = track
        selected = ref
        firstTap = null
        segmentId = null
    }

    /** Whether corrections can be stored (the track is saved); set by the screen. */
    internal var correctable = true

    /** Picks the corner or straight [id] to correct (none with null); not while a line is placed. */
    fun selectSegment(id: String?) {
        if (selected != null || id != null && !correctable) return
        segmentId = id?.takeIf { identity?.segment(it) != null }
        dragged = null
        correctionNote = null
    }

    /** [edit] if it applies to the corners as shown, null if not (so its button is off). */
    fun ifApplies(edit: SegmentEdit): SegmentEdit? =
        edit.takeIf { !correcting && identity?.corrected(edit).let { it != null && it != identity } }

    /**
     * The edit to store, marking a correction under way: the caller stores it and reports back with
     * [correctionStored]. Null when it does not apply.
     */
    fun startCorrection(edit: SegmentEdit): SegmentEdit? = ifApplies(edit)?.also {
        correcting = true
        correctionNote = null
    }

    /**
     * After the caller stored a correction: [stored] is the track as stored now (null if it is gone),
     * [applied] whether the correction is in it. When it is not (the corners changed meanwhile, as
     * after an analysis or a restore), the corners shown become the stored ones and the driver is told.
     */
    fun correctionStored(stored: Track?, applied: Boolean) {
        correcting = false
        dragged = null
        identity = stored?.identity
        identityLength = identity?.lengthMeters ?: 0.0
        if (selectedSegment == null) segmentId = null
        correctionNote = if (applied) null else "The corners changed meanwhile. They are shown as they are now."
    }

    /** The two ends of [selectedSegment] that can be dragged, with where each is. */
    fun segmentEnds(): List<Pair<SegmentEnd, GeoPoint>> {
        val r = identity
        val s = selectedSegment
        if (r == null || s == null) return emptyList()
        val i = r.segments.indexOf(s)
        val ends = listOfNotNull(
            SegmentEnd.Start.takeIf { r.closed || i > 0 },
            SegmentEnd.End.takeIf { r.closed || i < r.segments.size - 1 },
        )
        return ends.map { end ->
            val stored = if (end == SegmentEnd.Start) s.startMeters else s.endMeters
            end to r.pointAt(dragged?.takeIf { it.first == end }?.second ?: stored)
        }
    }

    /** Drags [end] of [selectedSegment] to the point of the track nearest [to], never past its neighbours. */
    fun dragBoundary(end: SegmentEnd, to: GeoPoint) {
        val r = identity
        val s = selectedSegment
        val m = r?.takeIf { !correcting }?.metersNear(to)
        if (r == null || s == null || m == null) return
        val i = r.segments.indexOf(s)
        val n = r.segments.size
        // The boundary moves between the start of the segment before it and the end of the one after it.
        val (from, until) = if (end == SegmentEnd.Start) {
            r.segments[(i - 1 + n) % n].startMeters to s.endMeters
        } else {
            s.startMeters to r.segments[(i + 1) % n].endMeters
        }
        dragged = end to clampAlong(from, m, until, r.closed)
    }

    /** The move of the dragged end, once let go; null when it did not move. */
    fun endDrag(): SegmentEdit? {
        val d = dragged
        val r = identity
        val s = selectedSegment
        if (d == null || r == null || s == null) return null
        val (end, at) = d
        val i = r.segments.indexOf(s)
        val id = if (end == SegmentEnd.Start) s.id else r.segments[(i + 1) % r.segments.size].id
        val edit = startCorrection(SegmentEdit.MoveBoundary(id, at))
        if (edit == null) dragged = null
        return edit
    }

    /** [m] kept at least [MinCorrectedSegmentMeters] after [from] and before [until], in driving order. */
    private fun clampAlong(from: Double, m: Double, until: Double, closed: Boolean): Double {
        val length = identityLength
        fun span(a: Double, b: Double) = if (closed) (b - a).mod(length) else b - a
        val whole = span(from, until)
        var head = span(from, m)
        // Outside the two segments on a loop: to the nearer of their ends.
        if (closed && head > whole) head = if (head - whole < length - head) whole else 0.0
        val most = maxOf(MinCorrectedSegmentMeters, whole - MinCorrectedSegmentMeters)
        val kept = head.coerceIn(MinCorrectedSegmentMeters, most)
        return if (closed) (from + kept).mod(length) else from + kept
    }

    /** Starts placing a new sector line, after the existing ones. */
    fun addSector() = select(LineRef.Sector(track.sectorLines.size))

    val canAddSector: Boolean get() = track.sectorLines.size < Track.MaxSectorLines

    fun onTap(at: GeoPoint) {
        val ref = selected ?: return selectSegmentAt(at)
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

    /** Picks the corner or straight at [at] on the map, if it is that close to the track; else none. */
    private fun selectSegmentAt(at: GeoPoint) {
        val r = identity ?: return
        selectSegment(r.metersNear(at, SegmentTapMeters)?.let { r.segmentAt(it).id })
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

    /** Renames the track; a blank or too long name is ignored. */
    fun rename(name: String) {
        val n = name.trim()
        if (n.isNotEmpty() && n.length <= Track.MaxNameChars) track = track.copy(name = n)
    }

    /**
     * Makes the track a circuit or point to point (FBBD-155). Lines are kept either way, so switching
     * back loses nothing; a point-to-point track is timed once its finish line is placed too.
     */
    fun setKind(kind: TrackKind) {
        if (selected == null) track = track.copy(kind = kind)
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
        proposed = false
        identity = stored.identity
        identityLength = identity?.lengthMeters ?: 0.0
    }

    private fun close() {
        selected = null
        firstTap = null
        beforePlacing = null
    }
}

/** One end of the corner or straight being corrected. */
enum class SegmentEnd { Start, End }

/** A tap this close to the track picks the corner or straight there, in metres. */
private const val SegmentTapMeters = 30.0

/** The editor's name for a line (UI-23); a point-to-point track's start/finish line is its start. */
fun LineRef.label(pointToPoint: Boolean = false): String = when (this) {
    LineRef.StartFinish -> if (pointToPoint) "Start" else "Start / finish"
    is LineRef.Sector -> "Sector line ${index + 1}"
    LineRef.PitEntry -> "Pit entry"
    LineRef.PitExit -> "Pit exit"
    LineRef.Finish -> "Finish"
}

/** The short name drawn next to a line on the map. */
fun LineRef.mapLabel(pointToPoint: Boolean = false): String = when (this) {
    LineRef.StartFinish -> if (pointToPoint) "Start" else "S/F"
    is LineRef.Sector -> "S${index + 1}"
    LineRef.PitEntry -> "Pit in"
    LineRef.PitExit -> "Pit out"
    LineRef.Finish -> "Finish"
}
