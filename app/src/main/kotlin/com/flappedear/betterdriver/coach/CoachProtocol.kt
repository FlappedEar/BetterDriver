package com.flappedear.betterdriver.coach

import android.content.Context
import java.io.File

/**
 * Messages between the recorder process and [CoachService], which runs in its own process
 * (`:coach`, FBBD-98). The recorder binds to the coach for each recording and sends it the session,
 * the detected track and every fix; the coach sends back events to log and its line for the track
 * screen ([com.flappedear.betterdriver.recording.RecorderProtocol.MSG_COACH_EVENT] and
 * [com.flappedear.betterdriver.recording.RecorderProtocol.MSG_COACH_STATUS]).
 */
internal object CoachProtocol {
    /** Recorder to coach: a recording began. msg.replyTo is where the coach reports. */
    const val MSG_SESSION = 1

    /** Recorder to coach: where the recording is; [TRACK_ID] is absent when not at a saved track. */
    const val MSG_TRACK = 2

    /** Recorder to coach: one fix. */
    const val MSG_FIX = 3

    const val SESSION = "session"
    const val START_NANOS = "startNanos"
    const val TRACK_ID = "trackId"
    const val T_US = "t"
    const val LAT = "lat"
    const val LON = "lon"

    /** Km/h, or NaN when the receiver gave none. */
    const val SPEED = "speed"

    /** In a coach report: event time on the session clock, kind and text. */
    const val EVENT_KIND = "kind"
    const val EVENT_TEXT = "text"

    /** In a status report: the coach line's corner label and text, each absent when empty. */
    const val STATUS_CORNER = "corner"
    const val STATUS_TEXT = "statusText"
}

/** Coach files: settings, and the per-track references with their session index. */
internal fun coachDir(context: Context) = File(context.filesDir, "coach")

internal fun coachSettings(context: Context) = CoachSettingsStore(File(coachDir(context), "settings.properties"))

internal fun coachReferencesDir(context: Context) = File(coachDir(context), "references")

/** The corners picked in the lap analysis for the coach, one file per track (FBBD-148). */
internal fun coachPicks(context: Context) = CoachPicksStore(File(coachDir(context), "picks"))

/** The next-session focus of each analysed session (FBBD-20), by track. */
internal fun sessionFocusStore(context: Context) = SessionFocusStore(File(coachDir(context), "focus"))
