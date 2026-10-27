package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.SessionStore
import java.io.IOException

/**
 * The review of session [id] in [store]. Only its events are read, so an hour at 25 Hz is not
 * held in memory for them; a missing or damaged session has no cues, never a crash. Run it off
 * the main thread.
 */
@Suppress("TooGenericExceptionCaught") // As the session list's cue counts (CoachedSessions).
fun CueReview.Companion.read(store: SessionStore, id: String): CueReview = try {
    CueReview.of(store.read(store.dir(id), samples = false).events)
} catch (_: IOException) {
    CueReview(emptyList(), 0)
} catch (_: RuntimeException) {
    CueReview(emptyList(), 0)
}
