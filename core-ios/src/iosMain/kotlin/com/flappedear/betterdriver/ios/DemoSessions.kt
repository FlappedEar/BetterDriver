@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.coach.CoachReplay
import com.flappedear.betterdriver.coach.toCoachFix
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionWriter
import com.flappedear.betterdriver.timing.demo.SessionReplay
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

/** The demo replays are read at this rate, as DemoParityTest reads them. */
private const val DemoRateHz = 10
private const val MicrosPerSecond = 1_000_000L
private const val DemoEndReason = "demo"

/**
 * The Android app's built-in demo replays (assets/demo) as finished sessions (FBBD-331): each is
 * driven once round its loop, as DemoParityTest reads it, so the iPad review of it shows the laps
 * and line plan that test checks on every platform. The app's UI tests add them to have real
 * sessions to open.
 */
internal object DemoSessions {
    /**
     * Writes the replay [replay] at [track] into [store] as a session starting at [startUtcMillis],
     * and saves [track] in [tracksRoot] (<id>.json) so the review finds it. [coached]: the coach rides
     * along and logs its cues, so the session has a coach review (FBBD-99). Returns the session's id.
     */
    @Suppress("LongParameterList") // Where it goes, what was driven where, when, and whether coached.
    fun add(
        store: SessionStore,
        tracksRoot: String,
        replay: String,
        track: Track,
        startUtcMillis: Long,
        coached: Boolean = false,
    ): String {
        saveTrack(tracksRoot, track)
        val script = SessionReplay.read(replay, startUtcMillis, DemoRateHz)
        val loopSeconds = replay.lineSequence().last { it.isNotBlank() }.substringBefore(' ').toDouble()
        val fixes = (loopSeconds * DemoRateHz).toLong()
        val info = SessionInfo(startUtcMillis, track = track.id, channels = GnssChannels.ALL)
        val writer = SessionWriter(store, info)
        // [coached]: the coach's events, as a drive with the spoken coach on logs them (FBBD-99).
        val coach = if (coached) CoachReplay(track) else null
        try {
            writer.open(GnssChannels.ALL)
            for (n in 0 until fixes) {
                val set = SampleSet(micros(n), script.fix(n))
                writer.write(Record.Samples(set))
                val fix = coach?.let { set.toCoachFix() } ?: continue
                for (e in coach.onFix(fix)) writer.write(Record.Event(e))
            }
            val end = micros(fixes)
            writer.write(Record.End(end, DemoEndReason))
            writer.close()
            writer.writeFinished(DemoEndReason, end)
        } finally {
            writer.release()
        }
        return writer.id
    }

    private fun micros(n: Long) = n * MicrosPerSecond / DemoRateHz

    private fun saveTrack(tracksRoot: String, track: Track) {
        NSFileManager.defaultManager.createDirectoryAtPath(tracksRoot, true, null, null)
        val text = NSString.create(string = TrackJson.encode(track))
        check(text.writeToFile("$tracksRoot/${track.id}.json", true, NSUTF8StringEncoding, null)) {
            "could not save the track ${track.id}"
        }
    }
}
