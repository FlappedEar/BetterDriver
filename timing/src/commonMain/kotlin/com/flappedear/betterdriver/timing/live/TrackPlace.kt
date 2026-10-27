package com.flappedear.betterdriver.timing.live

import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackDetection

// Common code (FBBD-331), so the file import names and gates a session on iOS as on Android.

/** The track's name for screens: a saved track, or the directory circuit the car is at. */
val TrackDetection.placeName: String?
    get() = when (this) {
        is TrackDetection.Saved -> track.displayName
        is TrackDetection.Directory -> circuit.name
        TrackDetection.Unknown -> null
    }

/** The start/finish line as the recorder's gate, for the session metadata and the VBO export. */
fun Track.startFinishGate(): Gate? = startFinish?.let {
    Gate("Start / Finish", it.left.lat, it.left.lon, it.right.lat, it.right.lon)
}
