package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.SystemClock
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** The coach library over the app's sessions, tracks and coach references (FBBD-168). */
internal fun coachLibrary(context: Context): CoachLibrary {
    val tracks = TrackStore(tracksDir(context))
    return CoachLibrary(SessionStore(sessionsDir(context)), coachReferencesDir(context)) { tracks.all() }
}

/** How often a refresh looks whether a recording started, in milliseconds. */
private const val RecordingCheckMillis = 2_000L

/**
 * Brings the coach references up to date in the UI process, in the background (FBBD-168), so the coach
 * starts at once at the next outing: after a track is saved, edited or deleted (a track made from a
 * session included), and when the sessions change. Never while a session records: a refresh that
 * finds one stops between sessions and leaves the rest to the coach or the next refresh. Requests that
 * arrive while one is queued are folded into it.
 */
internal object CoachReferences {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coach-references").apply { priority = Thread.MIN_PRIORITY }
    }
    private val queued = AtomicBoolean(false)

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return
        io.execute {
            queued.set(false)
            refreshNow(app)
        }
    }

    @Suppress("TooGenericExceptionCaught") // A failed refresh only means the coach builds the reference itself.
    private fun refreshNow(context: Context) {
        try {
            // The coach is off, or says only the basics: it has no use for references.
            if (coachSettings(context).read().verbosity < Verbosity.Focus) return
            val sessions = SessionStore(sessionsDir(context))
            var checkedAt = 0L
            var recording = false
            val stop = {
                val now = SystemClock.elapsedRealtime()
                if (now - checkedAt >= RecordingCheckMillis) {
                    checkedAt = now
                    recording = sessions.list().any { it.state == SessionState.RECORDING }
                }
                recording
            }
            // Waits for a restore (FBBD-156), so nothing is written into a half-swapped folder.
            AppDataGate.write { coachLibrary(context).refresh(stop) }
        } catch (e: Exception) {
            DebugLog.w(Tag, "coach references not refreshed", e)
        }
    }

    private const val Tag = "CoachReferences"
}
