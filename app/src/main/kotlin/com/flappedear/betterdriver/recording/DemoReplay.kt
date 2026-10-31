package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.analysis.demo.DemoTrack
import com.flappedear.betterdriver.debug.DebugGuards
import com.flappedear.betterdriver.recorder.fake.GnssScript
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.track.TrackStore

/**
 * What the Demo track receiver drives (FBBD-235): the made-up demo circuit, Arek's laps built into the
 * app (FBBD-237, FBBD-254), or a session on the phone replayed lap after lap (Settings › Equipment ›
 * Demo track › Drives). The shared [DemoTrack] decides and logs it, as on the iPhone (FBBD-373); this is
 * Android's files: the choice in the app's files folder, which the recorder process reads at each session
 * start, and the built-in replays in assets/demo.
 */
object DemoReplay {
    /** The choice of the Silesia Ring replay built into the app (assets/demo): Arek's 27 Sep sessions. */
    const val SilesiaRing = DemoTrack.SilesiaRing

    /** The choice of the Autodrom Jastrząb replay built into the app (assets/demo): Arek's 29 Aug sessions. */
    const val Jastrzab = DemoTrack.Jastrzab

    /** The chosen session's id, or null for the demo circuit. */
    fun sessionId(context: Context): String? = DebugGuards.allowDisk { DemoTrack.chosen(context.filesDir.path) }

    /** Chooses session [id] to replay, or the demo circuit when null. One small file: fine on the main thread. */
    fun choose(context: Context, id: String?) = DebugGuards.allowDisk { DemoTrack.choose(context.filesDir.path, id) }

    /** The fixes for a session starting now ([DemoTrack.script]). */
    fun script(context: Context, now: () -> Long = System::currentTimeMillis): GnssScript = DemoTrack.script(
        id = sessionId(context),
        tracks = TrackStore(tracksDir(context)),
        asset = { file -> context.assets.open("demo/$file").use { it.readBytes().decodeToString() } },
        session = { id -> SessionStore(sessionsDir(context)).read(id) },
        now = now,
    )
}
