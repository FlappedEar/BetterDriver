package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.demo.DemoChoice
import com.flappedear.betterdriver.analysis.demo.DemoSession
import com.flappedear.betterdriver.analysis.demo.DemoTrack
import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.fake.ScriptFeed
import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.iosTrackStore
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

private const val MillisPerSecond = 1_000.0

/**
 * The iPhone's Demo track (FBBD-373), as Android's (FBBD-233, FBBD-235): the shared [DemoTrack] with the
 * app's folders. [filesDir] holds the choice (Application Support, as Android's files folder),
 * [sessionsDir] and [tracksDir] the app's sessions and tracks, [demoDir] the built-in replays bundled
 * with the app (the Android app's assets/demo).
 */
object DemoTrackCore {
    /**
     * The Drives list, labelled as the Sessions list labels sessions.
     * Reads every session's meta: off the main queue.
     */
    fun choices(sessionsDir: String): List<DemoChoice> {
        val sessions = SessionStore(sessionsDir).list().map {
            DemoSession(it.id, it.info.startUtcMillis, it.info.track, it.state == SessionState.RECORDING)
        }
        return DemoTrack.choices(sessions, ::sessionLabel)
    }

    /** The chosen drive's id, null for the demo circuit. */
    fun chosen(filesDir: String): String? = DemoTrack.chosen(filesDir)

    fun choose(filesDir: String, id: String?) = DemoTrack.choose(filesDir, id)

    /** The choice [id] in [choices], or the demo circuit when it is not there. */
    fun current(choices: List<DemoChoice>, id: String?): DemoChoice = DemoTrack.current(choices, id)

    /** The chosen drive's name without reading the sessions: a built-in's or the circuit's, else "Session". */
    fun chosenLabel(filesDir: String): String {
        val id = chosen(filesDir) ?: return DemoTrack.Circuit.label
        return DemoTrack.builtIns.firstOrNull { it.id == id }?.trackName ?: "Session"
    }

    /**
     * The chosen drive's fixes feeding [sink] (a recording), its track saved first as on Android. Call
     * [ScriptFeed.start], then [ScriptFeed.advance] on every tick.
     */
    @Throws(Exception::class)
    fun feed(sink: SampleSink, filesDir: String, sessionsDir: String, tracksDir: String, demoDir: String): ScriptFeed {
        val script = DemoTrack.script(
            id = chosen(filesDir),
            tracks = iosTrackStore(tracksDir),
            asset = { CoreFiles.readText(CoreFiles.join(demoDir, it)) },
            session = { SessionStore(sessionsDir).read(it) },
            now = { (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong() },
        )
        return ScriptFeed(script, sink)
    }
}
