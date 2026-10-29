package com.flappedear.betterdriver.timing.track

import com.flappedear.betterdriver.timing.geo.GeoPoint

// The driver's corrections of a track's corners and straights in the track editor (FBBD-179), as
// Android's TrackEditorState and EditorCorners do them, without Compose: what the list, the
// controls and the map show, and the edit each control makes. The iPad and iPhone editor
// (FBBD-366) uses it; the caller stores each correction as it is made.

/** A corner or straight in the editor's list: its name, its number too when it has a name of its own. */
class SegmentListRow(
    val id: String,
    val name: String,
    /** "Corner 3" beside a name of the driver's own, so it can be found on the map; else null. */
    val number: String?,
    /** Not as detected: renamed, retyped, moved, or made by a correction. */
    val edited: Boolean,
    val straight: Boolean,
)

/** Corner, Chicane or Straight for the segment being corrected: on when it is that, off when it cannot become it. */
class SegmentTypeChip(val label: String, val selected: Boolean, val enabled: Boolean, val edit: SegmentEdit)

/** An end of the segment being corrected, where it is now (as dragged), to draw and drag. */
class SegmentHandle(val start: Boolean, val point: GeoPoint)

/** A segment's stretch of the centreline on the map, with a corner's number ("3", "3–4") at its middle. */
class SegmentStroke(
    val id: String,
    val corner: Boolean,
    val selected: Boolean,
    val points: List<GeoPoint>,
    val number: String?,
)

/** The end of the segment being corrected that is dragged, and where, in metres along the track. */
class DraggedEnd(val start: Boolean, val meters: Double)

/**
 * The track's corners and straights as the editor shows and corrects them (FBBD-179): pick one
 * ([select], or [selectAt] for a tap on the map), then retype, rename, split, merge or remove it
 * ([startCorrection] with the edit of a control), or drag its ends along the track ([drag],
 * [endDrag]). The caller stores each edit [startCorrection] or [endDrag] gives and reports back with
 * [correctionStored]; nothing else is offered meanwhile.
 */
@Suppress("TooManyFunctions") // One function per control, as Android's editor state.
class CornerCorrector(identity: TrackRevision?, correctable: Boolean = true) {
    /** The corners and straights as stored; null before any analysis at the track. */
    var identity: TrackRevision? = identity
        private set

    /** Whether corrections can be stored: the track is saved. */
    var correctable: Boolean = correctable
        private set

    /** The centreline's length, for the arithmetic of a drag. */
    private var length = identity?.lengthMeters ?: 0.0

    /** The corner or straight being corrected, by id, or null. */
    var segmentId: String? = null
        private set

    val selectedSegment: RevisionSegment? get() = segmentId?.let { identity?.segment(it) }

    /** While an end of [selectedSegment] is dragged: which and where. */
    var dragged: DraggedEnd? = null
        private set

    /** A correction is being stored. */
    var correcting: Boolean = false
        private set

    /** Why the last correction was not kept, or null. */
    var correctionNote: String? = null
        private set

    private var pathsOf: TrackRevision? = null
    private var paths: Map<String, List<GeoPoint>> = emptyMap()

    /** Picks the corner or straight [id] to correct; null for none. */
    fun select(id: String?) {
        if (id != null && !correctable) return
        segmentId = id?.takeIf { identity?.segment(it) != null }
        dragged = null
        correctionNote = null
    }

    /** Picks the corner or straight at [at] on the map, if the track is that close; else none. */
    fun selectAt(at: GeoPoint) {
        val r = identity ?: return
        select(r.metersNear(at, SegmentTapMeters)?.let { r.segmentAt(it).id })
    }

    /** [edit] if it changes the corners as shown, null if not (so its control is off). */
    fun ifApplies(edit: SegmentEdit): SegmentEdit? =
        edit.takeIf { !correcting && identity?.corrected(edit).let { it != null && it != identity } }

    /** Whether [edit] changes the corners as shown; for Swift. */
    fun applies(edit: SegmentEdit): Boolean = ifApplies(edit) != null

    /** The edit to store, marking a correction under way; null when it does not apply. */
    fun startCorrection(edit: SegmentEdit): SegmentEdit? = ifApplies(edit)?.also {
        correcting = true
        correctionNote = null
    }

    /**
     * After the caller stored a correction: [stored] is the identity as stored now (null when the
     * track is gone), [applied] whether the correction is in it; when not, the corners shown become
     * the stored ones and the driver is told.
     */
    fun correctionStored(stored: TrackRevision?, applied: Boolean) {
        correcting = false
        dragged = null
        show(stored)
        if (selectedSegment == null) segmentId = null
        correctionNote = if (applied) null else ChangedMeanwhileNote
    }

    /** After the track was saved: [stored] is its identity as stored, and corrections can be made. */
    fun saved(stored: TrackRevision?) {
        show(stored)
        correctable = true
        if (selectedSegment == null) segmentId = null
    }

    private fun show(r: TrackRevision?) {
        identity = r
        length = r?.lengthMeters ?: 0.0
    }

    /** The ends of [selectedSegment] that can be dragged: a point-to-point track's first and last have one. */
    fun handles(): List<SegmentHandle> {
        val r = identity
        val s = selectedSegment
        if (r == null || s == null) return emptyList()
        val i = r.segments.indexOf(s)
        return listOfNotNull(true.takeIf { r.closed || i > 0 }, false.takeIf { r.closed || i < r.segments.size - 1 })
            .map { start ->
                val at = dragged?.takeIf { it.start == start }?.meters ?: if (start) s.startMeters else s.endMeters
                SegmentHandle(start, r.pointAt(at))
            }
    }

    /**
     * Drags the [start] or end of [selectedSegment] to the point of the track nearest [to], never
     * past its neighbours.
     */
    fun drag(start: Boolean, to: GeoPoint) {
        val r = identity
        val s = selectedSegment
        val m = r?.takeIf { !correcting }?.metersNear(to)
        if (r == null || s == null || m == null) return
        val i = r.segments.indexOf(s)
        val n = r.segments.size
        // The boundary moves between the start of the segment before it and the end of the one after it.
        val from = if (start) r.segments[(i - 1 + n) % n].startMeters else s.startMeters
        val until = if (start) s.endMeters else r.segments[(i + 1) % n].endMeters
        dragged = DraggedEnd(start, clampAlong(from, m, until, r.closed))
    }

    /** The move of the dragged end once let go, started as [startCorrection]; null when it did not move. */
    fun endDrag(): SegmentEdit? {
        val d = dragged
        val r = identity
        val s = selectedSegment
        if (d == null || r == null || s == null) return null
        val i = r.segments.indexOf(s)
        val id = if (d.start) s.id else r.segments[(i + 1) % r.segments.size].id
        val edit = startCorrection(SegmentEdit.MoveBoundary(id, d.meters))
        if (edit == null) dragged = null
        return edit
    }

    /** [m] kept at least [MinCorrectedSegmentMeters] after [from] and before [until], in driving order. */
    private fun clampAlong(from: Double, m: Double, until: Double, closed: Boolean): Double {
        fun span(a: Double, b: Double) = if (closed) (b - a).mod(length) else b - a
        val whole = span(from, until)
        var head = span(from, m)
        // Outside the two segments on a loop: to the nearer of their ends.
        if (closed && head > whole) head = if (head - whole < length - head) whole else 0.0
        val most = maxOf(MinCorrectedSegmentMeters, whole - MinCorrectedSegmentMeters)
        val kept = head.coerceIn(MinCorrectedSegmentMeters, most)
        return if (closed) (from + kept).mod(length) else from + kept
    }

    /** The list under the lines. */
    fun rows(): List<SegmentListRow> {
        val r = identity ?: return emptyList()
        return r.segments.map {
            SegmentListRow(it.id, it.name, it.numberedName.takeIf { _ -> it.label != null }, r.edited(it.id),
                it.kind == SegmentKind.Straight)
        }
    }

    /** What the list says under its title. */
    val listNote: String
        get() = when {
            identity == null -> NoCornersNote
            !correctable -> SaveFirstNote
            identity?.corrected == true -> CorrectedNote
            else -> FoundNote
        }

    /** Restore automatic is offered: the corners are corrected and corrections can be stored. */
    val canRestore: Boolean get() = identity?.corrected == true && correctable

    /** Corner, Chicane and Straight for [selectedSegment]. */
    fun typeChips(): List<SegmentTypeChip> {
        val s = selectedSegment ?: return emptyList()
        return SegmentType.entries.map { type ->
            val edit = SegmentEdit.ChangeType(s.id, type)
            SegmentTypeChip(type.label, s.type == type, s.type == type || applies(edit), edit)
        }
    }

    /** Names [selectedSegment] [name]; empty goes back to its number. */
    fun renameEdit(name: String): SegmentEdit? = selectedSegment?.let { SegmentEdit.Rename(it.id, name) }

    /** Whether [name] can name a corner. */
    fun validName(name: String): Boolean = name.trim().length <= MaxSegmentLabelChars

    /** Splits [selectedSegment] at its middle along the track; the driver drags the new end from there. */
    fun splitEdit(): SegmentEdit? {
        val r = identity
        val s = selectedSegment
        if (r == null || s == null) return null
        val span = (s.endMeters - s.startMeters).let { if (r.closed) it.mod(length) else it }
        return SegmentEdit.Split(s.id, s.startMeters + span / 2)
    }

    fun mergeEdit(): SegmentEdit? = selectedSegment?.let { SegmentEdit.Merge(it.id) }

    fun removeEdit(): SegmentEdit? = selectedSegment?.let { SegmentEdit.Remove(it.id) }

    fun restoreEdit(): SegmentEdit = SegmentEdit.RestoreAutomatic

    /**
     * Each corner and straight's stretch of the centreline, the one being corrected last and drawn
     * as dragged; made again only when the corners change.
     */
    fun strokes(): List<SegmentStroke> {
        val r = identity ?: return emptyList()
        if (pathsOf !== r) {
            paths = r.segments.associate { it.id to r.path(it.startMeters, it.endMeters) }
            pathsOf = r
        }
        val selected = selectedSegment
        val d = dragged
        return r.segments.sortedBy { it.id == selected?.id }.map { s ->
            val isSelected = s.id == selected?.id
            val points = when {
                !isSelected || d == null -> paths[s.id].orEmpty()
                d.start -> r.path(d.meters, s.endMeters)
                else -> r.path(s.startMeters, d.meters)
            }
            val number = if (s.lastNumber > s.number) "${s.number}–${s.lastNumber}" else "${s.number}"
            SegmentStroke(s.id, s.kind == SegmentKind.Corner, isSelected, points,
                number.takeIf { s.kind == SegmentKind.Corner })
        }
    }
}

private val SegmentType.label: String
    get() = when (this) {
        SegmentType.Corner -> "Corner"
        SegmentType.Chicane -> "Chicane"
        SegmentType.Straight -> "Straight"
    }

/** A tap this close to the track picks the corner or straight there, in metres. */
private const val SegmentTapMeters = 30.0

const val CornersTitle = "Corners"
const val NoCornersNote = "Corners show here once a session at this track has been analysed."
const val SaveFirstNote = "Save the track to correct its corners."
const val CorrectedNote = "Corrected by you. Every session here is analysed with these."
const val FoundNote = "Found from your laps. Tap one here or on the map to correct it."
const val ChangedMeanwhileNote = "The corners changed meanwhile. They are shown as they are now."
const val SegmentDragNote = "Drag the round handles on the map along the track to move where it starts and ends. " +
    "Changes are kept as you make them and used for every session here."
const val RestoreCornersTitle = "Restore automatic corners?"
const val RestoreCornersText = "Your corrections to this track's corners are undone and the corners found from " +
    "your laps come back. Sessions keep all their data."
