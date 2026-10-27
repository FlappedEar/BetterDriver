@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.review.SessionReview
import com.flappedear.betterdriver.analysis.review.reviewOf
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.StoredSession
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackFormatException
import com.flappedear.betterdriver.timing.track.TrackJson
import com.flappedear.betterdriver.transfer.ReceivedBundle
import com.flappedear.betterdriver.transfer.SessionInbox
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/**
 * The app's sessions and tracks for the iPad review (FBBD-331), under [base] (the app's Application
 * Support folder): sessions in base/sessions, as the recorder writes them, tracks in base/tracks,
 * imports staged in base/transfer. Everything Swift calls that can fail is marked @Throws, so a bad
 * file becomes a Swift error instead of ending the app.
 */
class ReviewLibrary(base: String) {
    val sessionsRoot = "$base/sessions"
    private val tracksRoot = "$base/tracks"
    private val store = SessionStore(sessionsRoot)
    private val inbox = SessionInbox(sessionsRoot, "$base/transfer", tracksRoot)

    /** Every session with readable metadata, newest first; one still recording is left out. */
    fun sessions(): List<StoredSession> = store.list().filter { it.state != SessionState.RECORDING }

    /** Laps, best and theoretical best of session [id], as Android works them out. */
    @Throws(Exception::class)
    fun review(id: String): SessionReview = reviewOf(store.read(id), tracks())

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

    /** Adds the received session; with one of its id here, [replace] picks which stays. */
    @Throws(Exception::class)
    fun accept(received: ReceivedBundle, replace: Boolean): ImportOutcome =
        if (inbox.accept(received, replace)) ImportOutcome.ADDED else ImportOutcome.KEPT

    fun discard(received: ReceivedBundle) = inbox.discard(received)

    /** At app start: cleans up an import cut short. Returns whether a session came back. */
    fun clearStaging(): Boolean = inbox.clearStaging()
}

/** What became of a received session: added to the list, or the one already here kept. */
enum class ImportOutcome { ADDED, KEPT }
