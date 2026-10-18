package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.sessionanalysis.trackFor
import com.flappedear.betterdriver.timing.demo.DemoCircuit
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.File

/**
 * What the Demo track receiver drives (FBBD-235): the made-up demo circuit, or a session on the phone
 * replayed lap after lap, Arek's Silesia Ring outing for example (Settings › Devices › Demo track ›
 * Drives). The choice is the session id in a small file, so the recorder process reads the current
 * one at each session start; no file means the demo circuit.
 */
object DemoReplay {
    private fun file(context: Context) = File(context.filesDir, "demo_track_session")

    /** The chosen session's id, or null for the demo circuit. */
    fun sessionId(context: Context): String? = DebugGuards.allowDisk {
        file(context).takeIf { it.isFile }?.readText()?.trim()?.ifBlank { null }
    }

    /** Chooses session [id] to replay, or the demo circuit when null. One small file: fine on the main thread. */
    fun choose(context: Context, id: String?) {
        DebugGuards.allowDisk { if (id == null) file(context).delete() else file(context).writeText(id) }
        DebugLog.i("DemoReplay", "demo track drives ${id ?: "the demo circuit"}")
    }

    /**
     * The fixes for a session starting now: the chosen session's replay at its saved track, or the
     * demo circuit (its track saved first) when none is chosen or it cannot be replayed. Their UTC
     * starts at [now] read once the session is loaded, so a long read does not put it behind the
     * recording's own start.
     */
    // A session that cannot be read must not stop the recording: the demo circuit drives instead.
    @Suppress("TooGenericExceptionCaught")
    fun script(context: Context, now: () -> Long = System::currentTimeMillis): GnssScript {
        val id = sessionId(context)
        if (id != null) {
            try {
                val data = SessionStore(sessionsDir(context)).read(id)
                val track = trackFor(data, TrackStore(tracksDir(context)).all())
                SessionReplay.of(data.sampleSets, track, now())?.let {
                    DebugLog.i("DemoReplay", "replaying session $id at ${track?.id ?: "no saved track"}")
                    return it
                }
                DebugLog.w("DemoReplay", "session $id has no two timed laps at a saved track: demo circuit instead")
            } catch (e: Exception) {
                DebugLog.w("DemoReplay", "session $id cannot be replayed: demo circuit instead", e)
            }
        }
        val store = TrackStore(tracksDir(context))
        // A track already saved under its id is kept: Arek may have moved its lines.
        if (store.get(DemoCircuit.TrackId) == null) store.save(DemoCircuit.track)
        return DemoCircuit(now())
    }
}
