package com.flappedear.betterdriver

/** App destinations. */
object Routes {
    const val Setup = "setup"
    const val Drive = "drive"
    const val Sessions = "sessions"
    const val Settings = "settings"
    const val Pair = "pair"
    const val Track = "track"

    /** Settings opened on one tab, with a back button (from the first-run checklist). */
    const val SettingsTabPattern = "settings/{tab}"
    const val TabArg = "tab"

    fun settingsTab(tab: String) = "settings/$tab"

    /** Destinations that show the bottom bar (UI-11 option A). */
    val TopLevel = setOf(Drive, Sessions, Settings)
}
