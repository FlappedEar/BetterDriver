package com.flappedear.betterdriver.analysis.review

/**
 * What a tablet leaves out, the same on Android and iOS (Arek, 2026-10-30 and 2026-11-01): a tablet only
 * reviews, the phone records. So no Drive and no Car (its live OBD-II values), and in Settings none of
 * what sets up a recording: the equipment (RaceBox, OBD-II adapter, heart rate strap, cameras), the track
 * screen, the camera's video mode and the spoken coach. Cars, Tracks, App and Diagnostics stay, and so
 * does every recorded channel in a session's review.
 */
object ReviewOnlyDevice {
    /** The top-level destinations a tablet does not show, by their labels. */
    val hiddenTopLevel: Set<String> = setOf("Drive", "Car")

    /** The Settings topics a tablet does not show, by their titles. */
    val hiddenSettings: Set<String> = setOf("Equipment", "Track screen", "Video", "Spoken coach")

    /** Whether [label], a top-level destination, shows on this device. */
    fun showsTopLevel(label: String, tablet: Boolean): Boolean = !tablet || label !in hiddenTopLevel

    /** Whether [title], a Settings topic or a row of the App page, shows on this device. */
    fun showsSetting(title: String, tablet: Boolean): Boolean = !tablet || title !in hiddenSettings
}
