package com.flappedear.betterdriver

/** App destinations. */
object Routes {
    const val Setup = "setup"
    const val Drive = "drive"
    const val Sessions = "sessions"
    const val Settings = "settings"
    const val Pair = "pair"
    const val Track = "track"
    const val Tracks = "tracks"

    /** The track editor for one saved track (FBBD-56). */
    const val TrackEditorPattern = "tracks/{id}"
    const val TrackIdArg = "id"

    fun trackEditor(id: String) = "tracks/$id"

    /** Settings opened on one tab, with a back button (from the first-run checklist). */
    const val SettingsTabPattern = "settings/{tab}"
    const val TabArg = "tab"

    fun settingsTab(tab: String) = "settings/$tab"

    /** Destinations that show the bottom bar (UI-11 option A). */
    val TopLevel = setOf(Drive, Sessions, Settings)
}
