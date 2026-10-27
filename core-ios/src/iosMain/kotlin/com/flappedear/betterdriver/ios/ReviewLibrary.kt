@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.analysis.review.LapVideoReview
import com.flappedear.betterdriver.analysis.review.ReviewedSession
import com.flappedear.betterdriver.analysis.review.indexedClips
import com.flappedear.betterdriver.analysis.review.reviewSession
import com.flappedear.betterdriver.analysis.review.savedTrackId
import com.flappedear.betterdriver.analysis.review.trackFor
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.transfer.ReceivedBundle
import com.flappedear.betterdriver.transfer.SessionInbox
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.stringWithContentsOfFile

/**
 * The app's sessions and tracks for the iPad review (FBBD-331), under [base] (the app's Application
 * Support folder): sessions in base/sessions, as the recorder writes them, tracks in base/tracks,
 * imports staged in base/transfer. Everything Swift calls that can fail is marked @Throws, so a bad
 * file becomes a Swift error instead of ending the app.
 */
@Suppress("TooManyFunctions") // The app's one Kotlin entry for the review: list, read, import.
class ReviewLibrary(base: String) {
    val sessionsRoot = "$base/sessions"
    private val tracksRoot = "$base/tracks"
    private val store = SessionStore(sessionsRoot)
    private val inbox = SessionInbox(sessionsRoot, "$base/transfer", tracksRoot)
    private val plans = IosLinePlanStore(linePlansDir(base))

    /** Every session with readable metadata, newest first; one still recording is left out. */
    fun sessions(): List<StoredSession> = store.list().filter { it.state != SessionState.RECORDING }

    /**
     * Laps, best, theoretical best and where to find time of session [id], as Android works them
     * out: the targets come from the other sessions at its track (LineSessions on Android). The
     * result keeps the analysis, so Compare can pick another lap without reading the session again.
     * The session's line plan is kept for the track's next coached session, as Android keeps it
     * (FBBD-333); a plan that cannot be saved only leaves the coach without one.
     */
    @Throws(Exception::class)
    fun review(id: String): ReviewedSession {
        val saved = tracks()
        val reviewed = reviewSession(store.read(id), saved) { track -> lineSessions(id, track, saved) }
        reviewed.linePlan?.let(plans::save)
        return reviewed
    }

    /**
     * Every other session at [track], newest first, read one at a time as the line analysis asks,
     * at most [MaxLineSessions] of the [MaxScannedSessions] newest. One that names another saved
     * track is skipped without reading its samples; one that cannot be read is left out.
     */
    // A session that cannot be read is left out, whatever the reason.
    @Suppress("TooGenericExceptionCaught")
    private fun lineSessions(id: String, track: Track, saved: List<Track>): Sequence<LineSession> =
        sessions().asSequence()
            .filter { it.id != id }
            .take(MaxScannedSessions)
            .mapNotNull { s ->
                try {
                    val named = savedTrackId(store.read(s.id, samples = false).events)
                    if (named != null && named != track.id) return@mapNotNull null
                    val data = store.read(s.id)
                    if (trackFor(data, saved)?.id != track.id) return@mapNotNull null
                    val session = telemetrySessionOf(data, track)
                    LineSession(session, deriveSourceLapSession(session), sessionLabel(s.info.startUtcMillis), s.id)
                } catch (_: Exception) {
                    null
                }
            }
            .take(MaxLineSessions)

    /**
     * The fastest lap of [reviewed] (session [id]) against timed lap [otherIndex] on video, as
     * Android's lap review; null when the review does not have both laps. Without video the laps
     * still compare.
     */
    fun videoReview(reviewed: ReviewedSession, id: String, otherIndex: Int): LapVideoReview? {
        val dir = "${store.dir(id)}/${VideoFiles.DIR}"
        val index = readSmall("$dir/$ClipIndexFile")?.let(::indexedClips).orEmpty()
        return reviewed.videoReview(otherIndex, index, dir) { NSFileManager.defaultManager.fileExistsAtPath(it) }
    }

    /** [value] with two decimals, as Android's screens write seconds. */
    fun twoDecimals(value: Double): String = fixed(value, 2)

    /** The saved tracks; a file that does not read as a track is skipped. */
    fun tracks(): List<Track> {
        val fm = NSFileManager.defaultManager
        val names = fm.contentsOfDirectoryAtPath(tracksRoot, null)?.filterIsInstance<String>().orEmpty()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            val text = NSString.stringWithContentsOfFile("$tracksRoot/$name", NSUTF8StringEncoding, null)
                ?: return@mapNotNull null
            try {
                TrackJson.decode(text)
            } catch (_: TrackFormatException) {
                null
            }
        }
    }

    /** Unpacks and checks the .bdsession at [path]; see SessionInbox.receive. */
    @Throws(Exception::class)
    fun receive(path: String, onProgress: (read: Long, total: Long) -> Unit): ReceivedBundle =
        inbox.receive(path, onProgress)

    /** A session with the received one's id is already here. */
    fun exists(received: ReceivedBundle): Boolean = inbox.exists(received)

    /** The bundle's track differs from the saved track of its id: ask before replacing it. */
    fun trackDiffers(received: ReceivedBundle): Boolean = inbox.trackDiffers(received)

    /**
     * Adds the received session; with one of its id here, [replace] picks which stays, and
     * [replaceTrack] whether the bundle's track replaces a saved one of its id.
     */
    @Throws(Exception::class)
    fun accept(received: ReceivedBundle, replace: Boolean, replaceTrack: Boolean): ImportOutcome =
        if (inbox.accept(received, replace, replaceTrack)) ImportOutcome.ADDED else ImportOutcome.KEPT

    fun discard(received: ReceivedBundle) = inbox.discard(received)

    /** At app start: cleans up an import cut short. Returns whether a session came back. */
    fun clearStaging(): Boolean = inbox.clearStaging()
}

/** At most this many other sessions at the track give targets, newest first (as on Android). */
private const val MaxLineSessions = 20

/** Of at most this many newest sessions at any track. */
private const val MaxScannedSessions = 60

private const val MillisPerSecond = 1000.0

/** The session's clip index in its video folder (Android's VideoIndex). */
private const val ClipIndexFile = "clips.json"

private fun readSmall(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

/** "29 Aug 2026 at 17:20" in the device's language, as Android's medium date and short time. */
private fun sessionLabel(startUtcMillis: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterShortStyle
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(startUtcMillis / MillisPerSecond))

/** What became of a received session: added to the list, or the one already here kept. */
enum class ImportOutcome { ADDED, KEPT }
