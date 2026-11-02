package com.flappedear.betterdriver.analysis.review

/**
 * Whether a session offers its video (FBBD-426, Arek 2026-11-02): Watch video on the session list,
 * the Video tab and "Realistic optimal video" under the lap times show only when the session has a
 * whole video file to play ([hasVideo]) and is not recording. With none they are left out, not greyed
 * out. The same rule on Android and iOS.
 */
fun offersVideo(hasVideo: Boolean, recording: Boolean): Boolean = hasVideo && !recording
