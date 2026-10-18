package com.flappedear.betterdriver.feature.track

import com.flappedear.betterdriver.core.ui.component.SectorState
import com.flappedear.betterdriver.timing.live.LiveTimingState

/**
 * The track screen with live timing (FBBD-12) filled in. Sectors are coloured against the best
 * time for each sector, as the approved sector strip (UI-05) specifies.
 */
fun TrackScreenState.withTiming(timing: LiveTimingState): TrackScreenState = copy(
    deltaSeconds = timing.deltaSeconds,
    lapNumber = timing.lapNumber.takeIf { it > 0 },
    lapMillis = timing.lapMillis,
    lastMillis = timing.lastMillis,
    bestMillis = timing.bestMillis,
    sectors = timing.sectors.map { SectorState(it.name, it.deltaVsBestSectorSeconds, it.current) },
)
