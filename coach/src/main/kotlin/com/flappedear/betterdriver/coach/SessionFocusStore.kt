package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.timing.geo.MaxLatitude
import com.flappedear.betterdriver.timing.geo.MaxLongitude
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

/**
 * One difference between the faster and the other passes through a focus corner (FBBD-20), as
 * stored: [metric] is `brakingPoint`, `minimumSpeed` or `exitSpeed`; [other] and [faster] are the
 * medians of the two groups in [unit] (metres along the lap for a braking point). [confidence] is
 * the lowest of the passes the change uses (FBBD-177); null in records from before it was kept.
 */
@Serializable
data class FocusChangeRecord(
    val metric: String,
    val other: Double,
    val faster: Double,
    val unit: String = "",
    val measured: Boolean = true,
    val confidence: String? = null,
)

/**
 * One corner recommended for the next session (FBBD-20), as stored. [cornerId] is the corner's
 * CornerId in revision [identityRevision] of the track's corner identity (FBBD-172); both are null
 * when the analysis's corners were not the track's, and [segmentId] is then the analysis's own id.
 * [lats] and [lons] are the corner's line on the analysed session's fastest lap, so the coach can
 * place it on a track without an identity; empty where the coach does not run.
 *
 * [id] names this recommendation for good: the coaching effectiveness history (FBBD-180) records
 * against it whether it was attempted, changed the driving, improved the corner and persisted.
 */
@Serializable
data class FocusTarget(
    val id: String,
    /** `primary` or `secondary`. */
    val role: String,
    val segmentId: String,
    val cornerId: String? = null,
    val identityRevision: Int? = null,
    /** "Corner 3", as the analysis names it. */
    val name: String,
    /** What the focus area is about: `sectorGap`, `repeatedLoss`, `brakingSpread`, `minimumSpeedSpread`. */
    val kind: String,
    /** `HIGH` or `MEDIUM`; nothing weaker is ever a focus (FBBD-177). */
    val confidence: String,
    /** What a typical pass leaves against the target (the second fastest pass), seconds; null when unknown. */
    val timeAvailableSeconds: Double? = null,
    /** How far [timeAvailableSeconds] can be trusted (FBBD-177); null in records from before it was kept. */
    val timeConfidence: String? = null,
    val changes: List<FocusChangeRecord> = emptyList(),
    /** The evidence in words, as the summary showed it. */
    val observation: String = "",
    val what: String = "",
    val lats: List<Double> = emptyList(),
    val lons: List<Double> = emptyList(),
) {
    init {
        // A damaged file must fail here, where it is read, never in the coach (as [CornerPick] does).
        require(role == Primary || role == Secondary) { "unknown role: $role" }
        require(lats.size == lons.size) { "a line needs as many longitudes as latitudes" }
        require(lats.all { it.isFinite() && it in -MaxLatitude..MaxLatitude }) { "latitude out of range" }
        require(lons.all { it.isFinite() && it in -MaxLongitude..MaxLongitude }) { "longitude out of range" }
        require(timeAvailableSeconds?.isFinite() != false) { "time available not finite" }
    }

    val primary: Boolean get() = role == Primary

    /** The corner as a coach pick, null without its line. */
    fun pick(sessionId: String): CornerPick? = if (lats.isEmpty()) {
        null
    } else {
        CornerPick(name, lats, lons, sessionId, cornerId, identityRevision)
    }

    companion object {
        const val Primary = "primary"
        const val Secondary = "secondary"

        /** The id of the recommendation of [segmentId] made after session [sessionId]. */
        fun idOf(sessionId: String, segmentId: String) = "$sessionId/$segmentId"
    }
}

/**
 * What one analysed session recommended working on next at its track (FBBD-20): the primary focus
 * first, then at most two secondary ones; none when nothing was clear enough. Kept per track and
 * session, so the history of recommendations stays (FBBD-180 adds what came of each).
 */
@Serializable
data class SessionFocusRecord(
    val format: String = Format,
    val version: Int = Version,
    val trackId: String,
    val sessionId: String,
    /** When the session started; the latest session's focus is the next session's. */
    val sessionStartUtcMillis: Long,
    val algorithm: String = "",
    val targets: List<FocusTarget> = emptyList(),
) {
    /** The targets as coach picks, the primary first. */
    fun picks(): List<CornerPick> = targets.sortedBy { !it.primary }.mapNotNull { it.pick(sessionId) }

    companion object {
        const val Format = "betterdriver-session-focus"
        const val Version = 1
    }
}

/**
 * The next-session focus of each analysed session (FBBD-20), one `<track id>/<session id>.json` in
 * [dir]. The app writes it when a session's analysis is made; the coach reads the track's latest at
 * each session start ([latest]), so the next session works on it first. Writes are synced and
 * renamed into place; a file that cannot be read counts as none.
 */
class SessionFocusStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun get(trackId: String, sessionId: String): SessionFocusRecord? =
        read(fileFor(trackId, sessionId))?.takeIf { it.trackId == trackId && it.sessionId == sessionId }

    /** Every session's record at [trackId], oldest first. */
    fun history(trackId: String): List<SessionFocusRecord> =
        File(dir, checkedTrackId(trackId)).listFiles { f -> f.isFile && f.name.endsWith(Suffix) }.orEmpty()
            .mapNotNull { f -> read(f)?.takeIf { it.trackId == trackId && f.name == it.sessionId + Suffix } }
            .sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))

    /**
     * The focus the next session at [trackId] works on: that of the latest session with one, not
     * [exclude] (the session being driven). A later session with nothing clear enough to work on
     * leaves the earlier focus standing: it does not show the earlier one was dealt with. A record
     * whose session is gone ([kept] false) is never used, even if deleting it failed.
     */
    fun latest(trackId: String, exclude: String? = null, kept: (String) -> Boolean = { true }): SessionFocusRecord? =
        history(trackId).lastOrNull { r -> r.sessionId != exclude && r.targets.isNotEmpty() && kept(r.sessionId) }

    /** Deletes session [sessionId]'s record at every track, with the session; returns whether none is left. */
    fun deleteSession(sessionId: String): Boolean {
        val name = checkedSessionId(sessionId) + Suffix
        val files = dir.listFiles { f -> f.isDirectory }.orEmpty().map { File(it, name) }
        files.forEach { it.delete() }
        return files.none { it.exists() }
    }

    /** Saves [record] unless the same is saved already; returns whether it wrote. */
    fun save(record: SessionFocusRecord): Boolean {
        val f = fileFor(record.trackId, record.sessionId)
        // One record per session: analysed again at another track, it leaves the old track (FBBD-20).
        val elsewhere = dir.listFiles { d -> d.isDirectory && d.name != record.trackId }.orEmpty()
            .map { File(it, f.name) }.filter { it.exists() }
        elsewhere.forEach { it.delete() }
        if (read(f) == record) return elsewhere.isNotEmpty()
        writeAtomically(f, json.encodeToString(SessionFocusRecord.serializer(), record))
        return true
    }

    /** Deletes every record of [trackId], with its track (FBBD-157); returns whether none is left. */
    fun delete(trackId: String): Boolean {
        val d = File(dir, checkedTrackId(trackId))
        d.deleteRecursively()
        return !d.exists()
    }

    private fun read(f: File): SessionFocusRecord? {
        if (!f.isFile) return null
        return try {
            json.decodeFromString(SessionFocusRecord.serializer(), f.readText())
                .takeIf { it.format == SessionFocusRecord.Format && it.version == SessionFocusRecord.Version }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun fileFor(trackId: String, sessionId: String) =
        File(File(dir, checkedTrackId(trackId)), checkedSessionId(sessionId) + Suffix)

    private companion object {
        const val Suffix = ".json"
    }
}

/** [sessionId], once it is known to be safe as a file name. */
internal fun checkedSessionId(sessionId: String): String {
    require(sessionId.isNotBlank() && sessionId.all { it.isLetterOrDigit() || it in "-_." } && sessionId != "." &&
        sessionId != "..") { "bad session id: $sessionId" }
    return sessionId
}
