package com.flappedear.betterdriver

/** App destinations. */
object Routes {
    const val Setup = "setup"
    const val Drive = "drive"
    const val Sessions = "sessions"
    const val Settings = "settings"

    /** Add equipment (UI-34 B): scan for a RaceBox or HERO11, or add one without Bluetooth. */
    const val AddEquipment = "add-equipment"

    /** One equipment item (UI-33 B): rename or remove. */
    const val EquipmentPattern = "equipment/{id}"
    const val EquipmentIdArg = "id"

    fun equipment(id: String) = "equipment/$id"

    /** Diagnostics for one RaceBox item (FBBD-89, UI-39). */
    const val DiagnosticsPattern = "equipment/{id}/diagnostics"

    fun diagnostics(id: String) = "equipment/$id/diagnostics"

    /** A car (UI-35 B); [NewCar] adds one. */
    const val CarPattern = "cars/{id}"
    const val CarIdArg = "id"
    const val NewCar = "new"

    fun car(id: String) = "cars/$id"

    /** Two laps side by side with their videos (FBBD-24). */
    const val Review = "review"

    /** Lap analysis of one saved session (FBBD-102). */
    const val SessionAnalysisPattern = "sessions/{id}/analysis"
    const val SessionIdArg = "id"

    fun sessionAnalysis(id: String) = "sessions/$id/analysis"

    /** The cue review of one session (FBBD-99). */
    const val CueReviewPattern = "sessions/{id}/cues"

    fun cueReview(id: String) = "sessions/$id/cues"

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
