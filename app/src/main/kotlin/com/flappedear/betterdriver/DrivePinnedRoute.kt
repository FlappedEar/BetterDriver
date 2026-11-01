package com.flappedear.betterdriver

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.flappedear.betterdriver.coach.sessionFocusStore
import com.flappedear.betterdriver.recorder.log.DebugLog
import com.flappedear.betterdriver.recording.Sessions
import com.flappedear.betterdriver.shell.DriveFocus
import com.flappedear.betterdriver.shell.DrivePinned
import com.flappedear.betterdriver.shell.driveFocus
import com.flappedear.betterdriver.shell.lastTimeHere
import com.flappedear.betterdriver.timing.live.placeName
import com.flappedear.betterdriver.timing.track.TrackDetection
import com.flappedear.betterdriver.tracks.Here
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Drive's Pinned cards (UI-308 A) for the track the phone is at ([here]): the focus the last session
 * there kept for the next one (FBBD-20), read off the main thread, and the last day there from the
 * listed [sessions] and their lap facts. Nothing while the track is unknown.
 */
@Composable
internal fun rememberDrivePinned(here: Here?, sessions: Sessions): DrivePinned {
    val context = LocalContext.current
    val trackId = (here?.detection as? TrackDetection.Saved)?.track?.id
    val ids = sessions.list.map { it.id }.toSet()
    val focus by produceState<DriveFocus?>(null, trackId, ids) {
        value = trackId?.let { id ->
            withContext(Dispatchers.IO) {
                try {
                    driveFocus(sessionFocusStore(context).latest(id, kept = { it in ids }))
                } catch (e: IOException) {
                    DebugLog.w(Tag, "next session focus at $id not read", e)
                    null
                }
            }
        }
    }
    val facts = rememberSessionFacts(sessions)
    val track = here?.detection?.placeName
    val last = remember(sessions.list, facts, track) { lastTimeHere(sessions.list, facts, track) }
    return DrivePinned(focus, last)
}

// The next-session focus is the session focus's log (FBBD-20).
private const val Tag = "SessionFocus"
