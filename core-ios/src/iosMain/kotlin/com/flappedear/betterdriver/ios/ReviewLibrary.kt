@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

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
import com.flappedear.betterdriver.coach.CueCountsText
import com.flappedear.betterdriver.coach.CueReview
import com.flappedear.betterdriver.coach.card
import com.flappedear.betterdriver.coach.notSaid
import com.flappedear.betterdriver.coach.rememberCueCounts
import com.flappedear.betterdriver.analysis.summary.SessionProgress
import com.flappedear.betterdriver.feature.analysis.CornerLapsUi
import com.flappedear.betterdriver.feature.analysis.TrackDayState
import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.store.DeleteResult
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.circuits.CircuitListException
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.transfer.ReceivedBundle
import com.flappedear.betterdriver.transfer.SessionInbox
import com.flappedear.betterdriver.transfer.SessionOutbox
import com.flappedear.betterdriver.transfer.foreign.ForeignInbox
import com.flappedear.betterdriver.transfer.foreign.ImportedSession
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

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
    private val days = IosDayPlanStore(dayPlansDir(base))
    private val trackFiles = TrackLibrary(tracksRoot, sessionsRoot)
    private val foreign = ForeignInbox(sessionsRoot, "$base/transfer")
    private val cueCountsFile = "$base/coach/cues.txt"
    private val cueCountLock = NSLock()

    /** The progression at each track (FBBD-181), its sessions' records kept while the app runs. */
    val progressions = TrackProgressions(store, trackFiles)

    /** Every session with readable metadata, newest first; one still recording is left out. */
    fun sessions(): List<StoredSession> = store.list().filter { it.state != SessionState.RECORDING }

    /** Deletes session [id] with everything in its folder, its video included, as Android (FBBD-90). */
    fun delete(id: String): DeleteResult = store.delete(id)

    /**
     * Writes session [id] as a .bdsession under [outRoot] to share, as Android's Share: its files,
     * video included, and the saved track it was timed with, so it opens with its laps on Android
     * or another iPhone. Returns the file's path; [onProgress] gets bytes written and their total.
     */
    @Throws(Exception::class)
    fun share(id: String, outRoot: String, onProgress: (written: Long, total: Long) -> Unit): String =
        SessionOutbox(sessionsRoot, outRoot).write(id, timedTrack(id), onProgress)

    /**
     * The saved track session [id] is timed with, as its review finds it: the one it recorded at
     * start (Android's share sends that one), else the saved track it was driven at. None if unreadable.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun timedTrack(id: String): Track? = try {
        trackFor(store.read(id), tracks())
    } catch (_: Exception) {
        null
    }

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
     * Session [id], reviewed as [reviewed], against the earlier sessions at its track, as Android's
     * summary card shows it (FBBD-122, FBBD-175); null when it is at no track or cannot be read.
     * Reads up to 60 sessions, so never on the main thread.
     */
    @Suppress("TooGenericExceptionCaught") // Swift cannot catch a Kotlin exception; it must not cross.
    fun progress(reviewed: ReviewedSession, id: String): SessionProgress? = try {
        val start = store.read(id, samples = false).info.startUtcMillis
        sessionProgress(store, reviewed, id, start, tracks(), ::sessionLabel)
    } catch (_: Exception) {
        null
    }

    /**
     * The track day of sessions [ids] (FBBD-315), as Android's track day page reads it; kept as the
     * track's day plan, so the next day there starts on its focus and measures it.
     */
    fun trackDay(ids: List<String>): TrackDayState = trackDayState(store, tracks(), days, trackFiles, ids)

    /**
     * The fastest lap of [reviewed] (session [id]) against timed lap [otherIndex] on video, as
     * Android's lap review; null when the review does not have both laps. Without video the laps
     * still compare.
     */
    fun videoReview(reviewed: ReviewedSession, id: String, otherIndex: Int): LapVideoReview? {
        val dir = videoDir(id)
        return reviewed.videoReview(otherIndex, clipIndex(dir), dir, ::fileExists)
    }

    /**
     * Every counted pass through corner [cornerId] of [reviewed] (session [id]) with its traces and
     * its clip of the session's video, as Android's Corner laps screen (FBBD-93, FBBD-153); null
     * without a counted lap. Off the main thread.
     */
    fun everyPass(reviewed: ReviewedSession, id: String, cornerId: String): CornerLapsUi? {
        val dir = videoDir(id)
        return reviewed.everyPass(cornerId, clipIndex(dir), dir, ::fileExists)
    }

    /**
     * Timed laps [aIndex] against [bIndex] of [reviewed] (session [id]) on video, as Android opens two
     * passes of a corner in the lap review; null when the review does not have both laps.
     */
    fun lapsOnVideo(reviewed: ReviewedSession, id: String, aIndex: Int, bIndex: Int): LapVideoReview? {
        val dir = videoDir(id)
        return reviewed.lapsOnVideo(aIndex, bIndex, clipIndex(dir), dir, ::fileExists)
    }

    private fun videoDir(id: String) = "${store.dir(id)}/${VideoFiles.DIR}"

    private fun clipIndex(dir: String) = readSmall("$dir/$ClipIndexFile")?.let(::indexedClips).orEmpty()

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

    /**
     * At app start, off the main thread: a session the app was killed in the middle of (no clean
     * stop) gets its recovery record and duration, as Android's start does (FBBD-71, audit BD-05).
     * Its samples are only read. Returns one line per session for the app's log, never throws:
     * a session that cannot be recovered is listed with why and left as it is.
     */
    @Suppress("TooGenericExceptionCaught") // Swift cannot catch a Kotlin exception; it must not cross.
    fun recoverInterrupted(): List<String> {
        val lines = mutableListOf<String>()
        try {
            store.recoverInterrupted { id, e -> lines += "$id not recovered: ${e.message}" }.forEach { r ->
                lines += "${r.id} recovered: ${r.sampleSets} sample sets to ${r.lastSampleMicros} us"
            }
        } catch (e: Exception) {
            lines += "recovery stopped: ${e.message}"
        }
        return lines
    }

    /**
     * Imports the VBO or RaceChrono CSV file at [path] as a new session (FBBD-164), as Android's
     * import does: the track is detected among the saved tracks and the built-in circuit list.
     * [onProgress] gets the bytes read so far and the file's size. Throws ImportFormatException,
     * with a message for the user, for a file that cannot be imported.
     */
    @Throws(Exception::class)
    fun importForeign(path: String, onProgress: (read: Long, total: Long) -> Unit): ImportedSession =
        foreign.importFile(path, path.substringAfterLast('/'), tracks(), circuits, onProgress)

    /**
     * The coach review of session [id] (FBBD-99), as Android's CueReviewRoute reads it: only its
     * events, so an hour at 25 Hz is not held in memory for them. A missing or damaged session has no
     * cues, never an error. Off the main thread.
     */
    // A session that cannot be read has no cues, whatever the reason.
    @Suppress("TooGenericExceptionCaught")
    fun cueReview(id: String): CoachReviewUi {
        val review = try {
            CueReview.of(store.read(id, samples = false).events)
        } catch (_: Exception) {
            CueReview(emptyList(), 0)
        }
        return CoachReviewUi(review.summary, review.notSaid, review.cues.map { it.card() })
    }

    /**
     * How many cues each listed session has, by id, for sessions with at least one (FBBD-99), as
     * Android's CoachedSessions: each session is read once and its count kept in base/coach by its id
     * and length. Off the main thread.
     */
    fun cueCounts(): Map<String, Int> {
        cueCountLock.lock()
        try {
            val known = readSmall(cueCountsFile)?.let(CueCountsText::decode) ?: mutableMapOf()
            val found = rememberCueCounts(sessions(), { it.id }, { it.durationMicros }, known, ::countCues)
            if (found.changed) {
                val dir = cueCountsFile.substringBeforeLast('/')
                NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
                // Only a cache: a count that is not kept is read again next time.
                NSString.create(string = CueCountsText.encode(known))
                    .writeToFile(cueCountsFile, true, NSUTF8StringEncoding, null)
            }
            return found.counts
        } finally {
            cueCountLock.unlock()
        }
    }

    // A damaged session counts as no cues; it is never a crash.
    @Suppress("TooGenericExceptionCaught")
    private fun countCues(s: StoredSession): Int = try {
        CueReview.count(store.read(s.id, samples = false).events)
    } catch (_: Exception) {
        0
    }

    /**
     * Adds the demo replay at [replayPath] at the track in [trackPath] (the Android app's built-in
     * demos) as a finished session starting at [startUtcMillis], for the app's UI tests; see
     * DemoSessions. Returns the session's id.
     */
    @Throws(Exception::class)
    fun addDemo(replayPath: String, trackPath: String, startUtcMillis: Long): String =
        addDemo(replayPath, trackPath, startUtcMillis, coached = false)

    /** [addDemo] with the spoken coach on: the session has the coach's cues to review (FBBD-99). */
    @Throws(Exception::class)
    fun addCoachedDemo(replayPath: String, trackPath: String, startUtcMillis: Long): String =
        addDemo(replayPath, trackPath, startUtcMillis, coached = true)

    private fun addDemo(replayPath: String, trackPath: String, startUtcMillis: Long, coached: Boolean): String {
        val replay = checkNotNull(NSString.stringWithContentsOfFile(replayPath, NSUTF8StringEncoding, null)) {
            "cannot read $replayPath"
        }
        val track = checkNotNull(NSString.stringWithContentsOfFile(trackPath, NSUTF8StringEncoding, null)) {
            "cannot read $trackPath"
        }
        return DemoSessions.add(store, tracksRoot, replay, TrackJson.decode(track), startUtcMillis, coached)
    }

    /** The circuit list bundled with the app (circuits.json, as Android's), or none. */
    private val circuits: CircuitList by lazy {
        val path = NSBundle.mainBundle.pathForResource("circuits", "json")
        val text = path?.let { NSString.stringWithContentsOfFile(it, NSUTF8StringEncoding, null) }
        try {
            text?.let(CircuitList::decode) ?: CircuitList.Empty
        } catch (_: CircuitListException) {
            CircuitList.Empty
        }
    }
}

/** At most this many other sessions at the track give targets, newest first (as on Android). */
private const val MaxLineSessions = 20

/** Of at most this many newest sessions at any track. */
private const val MaxScannedSessions = 60

private const val MillisPerSecond = 1000.0

/** The session's clip index in its video folder (Android's VideoIndex). */
private const val ClipIndexFile = "clips.json"

private fun fileExists(path: String): Boolean = NSFileManager.defaultManager.fileExistsAtPath(path)

private fun readSmall(path: String): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

/** "29 Aug 2026 at 17:20" in the device's language, as Android's medium date and short time. */
private fun sessionLabel(startUtcMillis: Long): String = NSDateFormatter().apply {
    dateStyle = NSDateFormatterMediumStyle
    timeStyle = NSDateFormatterShortStyle
}.stringFromDate(NSDate.dateWithTimeIntervalSince1970(startUtcMillis / MillisPerSecond))

/** What became of a received session: added to the list, or the one already here kept. */
enum class ImportOutcome { ADDED, KEPT }
