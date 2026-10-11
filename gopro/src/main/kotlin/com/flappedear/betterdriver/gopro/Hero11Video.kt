package com.flappedear.betterdriver.gopro

/**
 * A HERO11 video mode as Open GoPro setting values: [resolution] for setting 2 and [fps] for
 * setting 3 (see [OpenGoPro.Setting]). [label] is what the app shows, such as "1080p · 60".
 */
data class Hero11Video(val resolution: Int, val fps: Int, val label: String)
