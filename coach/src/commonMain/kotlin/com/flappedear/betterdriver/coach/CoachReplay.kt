package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.timing.track.Track

/**
 * The coach over a drive at [track] fed one fix after the other, as the Demo track receiver's session
 * is coached (DemoTrackReplayTest): no reference from earlier sessions, each fix handled on time and
 * every cue said. It gives the iPhone's demo sessions the coach events a coached drive logs, so their
 * coach review (FBBD-99) can be opened in the app's UI tests.
 */
class CoachReplay(track: Track, settings: CoachSettings = CoachSettings()) {
    private val coach = CoachSession(settings, track, reference = null)

    /** The coach events [fix] led to, to log in the session at their own times. */
    fun onFix(fix: CoachFix): List<RecorderEvent> =
        coach.onFix(fix, fix.tMicros) { true }.events.map { RecorderEvent(it.tMicros, it.kind, it.text) }
}
