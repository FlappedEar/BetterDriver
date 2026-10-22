package com.flappedear.betterdriver.coach

import android.content.Context
import android.os.SystemClock
import com.flappedear.betterdriver.analysis.identity.IdentityMigrations
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recording.sessionsDir
import com.flappedear.betterdriver.recording.tracksDir
import com.flappedear.betterdriver.sharing.AppDataGate
import com.flappedear.betterdriver.timing.track.TrackStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The coach library over the app's sessions, tracks and coach references (FBBD-168), aiming where
 * each track's line plan does (FBBD-307).
 */
internal fun coachLibrary(context: Context): CoachLibrary {
    val tracks = TrackStore(tracksDir(context))
    val lines = linePlanStore(context)
    return CoachLibrary(
        SessionStore(sessionsDir(context)),
        coachReferencesDir(context),
        tracks = { tracks.all() },
        plans = { id -> lines.get(id)?.let(::plannedTargets) },
    )
}

/** The targets of [plan] in its corners with a CornerId, as the coach reference takes them. */
internal fun plannedTargets(plan: LinePlan) = PlannedTargets(
    plan.sessionId,
    plan.corners.mapNotNull { c -> c.cornerId?.let { PlannedTarget(it, c.target.sessionId, c.target.lapSeconds) } },
)

/** How often a refresh looks whether a recording started, in milliseconds. */
private const val RecordingCheckMillis = 2_000L

/**
 * Brings the coach references up to date in the UI process, in the background (FBBD-168), so the coach
 * starts at once at the next outing: after a track is saved, edited or deleted (a track made from a
 * session included), and when the sessions change. Never while a session records: a refresh that
 * finds one stops between sessions and leaves the rest to the coach or the next refresh. Requests that
 * arrive while one is queued are folded into it.
 *
 * Each refresh also gives the stored tracks without corner ids their corner identity (FBBD-172), from
 * the sessions recorded there, so the coach names corners as the lap analysis does before any of them
 * is opened: at the first refresh after the app starts, and again whenever a track (new, imported,
 * restored or changed) or a session has come since; a refresh with nothing new skips it.
 */
internal object CoachReferences {
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "coach-references").apply { priority = Thread.MIN_PRIORITY }
    }
    private val queued = AtomicBoolean(false)
    private val identities = IdentityMigrations()

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
            AppDataGate.write { identities.runIfChanged(TrackStore(tracksDir(context)), sessions, stop) }
            // The coach is off, or says only the basics: it has no use for references.
            if (coachSettings(context).read().verbosity < Verbosity.Focus) return
            AppDataGate.write { coachLibrary(context).refresh(stop) }
        } catch (e: Exception) {
            DebugLog.w(Tag, "coach references not refreshed", e)
        }
    }

    private const val Tag = "CoachReferences"
}
