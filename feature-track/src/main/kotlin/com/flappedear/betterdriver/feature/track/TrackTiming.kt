package com.flappedear.betterdriver.feature.track

import com.flappedear.betterdriver.timing.live.LiveTimingState

/** The track screen with live timing (FBBD-12) filled in. Sector times left it with FBBD-262. */
fun TrackScreenState.withTiming(timing: LiveTimingState): TrackScreenState = copy(
    deltaSeconds = timing.deltaSeconds,
    lapNumber = timing.lapNumber.takeIf { it > 0 },
    lapMillis = timing.lapMillis,
    lastMillis = timing.lastMillis,
    bestMillis = timing.bestMillis,
)
