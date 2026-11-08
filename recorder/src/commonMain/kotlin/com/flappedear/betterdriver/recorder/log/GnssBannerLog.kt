package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.live.GnssState
import com.flappedear.betterdriver.recorder.live.LiveSnapshot

/**
 * Follows the track screen's GNSS-lost banner (FBBD-64): it shows while [LiveSnapshot.gnss] is
 * [GnssState.Lost]. Each change is one [EventKinds.GNSS_BANNER] event, which the drive's check
 * (ui.false_banners, ui.banner_show, ui.banner_clear) reads against the session's fixes.
 *
 * Common Kotlin, so the iPhone logs the banner as Android does: without it every iOS session with a
 * GNSS outage judged the banner as never shown (8 Nov 2026 log, two outages of 25 and 31 s).
 */
class GnssBannerLog {
    private var shown = false

    /** A new session starts with the banner hidden. */
    fun reset() {
        shown = false
    }

    /** The event text when the banner changes with [snapshot], else null. */
    fun follow(snapshot: LiveSnapshot): String? {
        val now = snapshot.gnss == GnssState.Lost
        if (now == shown) return null
        shown = now
        return "state=${if (now) "shown" else "cleared"} fix_age_us=${snapshot.lastFixAgeMicros ?: -1}"
    }
}
