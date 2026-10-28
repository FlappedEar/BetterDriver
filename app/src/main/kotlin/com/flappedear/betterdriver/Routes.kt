package com.flappedear.betterdriver

import android.net.Uri

/** App destinations. */
@Suppress("TooManyFunctions") // One route builder per destination with arguments.
object Routes {
    const val Setup = "setup"
    const val Drive = "drive"
    const val Sessions = "sessions"
    const val Settings = "settings"

    /** The next session's car and its live values, in the bottom bar (UI-136 B, UI-133 B). */
    const val Car = "car"

    /** Spoken coach settings, a page of their own (FBBD-98, UI-64 B). */
    const val CoachSettings = "settings/coach"

    /** The coach's voice, a list of the phone's voices (FBBD-240, UI-90 A). */
    const val CoachVoice = "settings/coach/voice"

    /** The OBD-II adapter's connection and live values, from Settings › Equipment (FBBD-261, UI-104 B). */
    const val Obd = "settings/obd"

    /** The camera test, opened from Settings › Diagnostics (FBBD-278, UI-150 A). */
    const val CameraTest = "settings/camera-test"

    /** About and credits, opened from Settings › App (FBBD-79). */
    const val About = "settings/about"

    /** Add equipment (UI-34 B): scan for a RaceBox or Open GoPro camera, or add one without Bluetooth. */
    const val AddEquipment = "add-equipment"

    /** One equipment item (UI-33 B): rename or remove. */
    const val EquipmentPattern = "equipment/{id}"
    const val EquipmentIdArg = "id"

    fun equipment(id: String) = "equipment/$id"

    /** Diagnostics for one RaceBox item (FBBD-89, UI-39). */
    const val DiagnosticsPattern = "equipment/{id}/diagnostics"

    fun diagnostics(id: String) = "equipment/$id/diagnostics"

    /** A car's page (UI-133 B): its live values when it is the next session's. */
    const val CarPagePattern = "car/{id}"

    fun carPage(id: String) = "car/$id"

    /** The PIDs a car reported, with a switch each (FBBD-344, UI-301 B). */
    const val CarPidsPattern = "car/{id}/pids"

    fun carPids(id: String) = "car/$id/pids"

    /** The CAN ids heard at a car's OBD port (FBBD-344). */
    const val CarCanPattern = "car/{id}/can"

    fun carCan(id: String) = "car/$id/can"

    /**
     * Adding or changing one of a car's own parameters, in steps (FBBD-344, UI-302 B): [NewParameter]
     * adds one, optionally on CAN id [CarCanArg] or mode 01 PID [CarPidArg] (hex).
     */
    const val CarParameterPattern = "car/{id}/parameter/{key}?can={can}&pid={pid}"
    const val CarParameterKeyArg = "key"
    const val CarCanArg = "can"
    const val CarPidArg = "pid"
    const val NewParameter = "new"
    private const val HexRadix = 16

    fun carParameter(id: String, key: String = NewParameter, can: Int? = null, pid: Int? = null): String {
        val query = listOfNotNull(
            can?.let { "can=${it.toString(HexRadix)}" },
            pid?.let { "pid=${it.toString(HexRadix)}" },
        )
        return "car/$id/parameter/$key" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }

    /** Editing a car's name and notes (UI-35 B); [NewCar] adds one. */
    const val CarPattern = "cars/{id}"
    const val CarIdArg = "id"
    const val NewCar = "new"

    fun car(id: String) = "cars/$id"

    /** Two laps side by side with their videos (FBBD-24). */
    const val Review = "review"

    /** The review opened on two laps (FBBD-153): each lap's key ("session:lap"), and where along lap A to start. */
    const val ReviewPattern = "review?a={a}&b={b}&at={at}"
    const val ReviewAArg = "a"
    const val ReviewBArg = "b"
    const val ReviewAtArg = "at"

    fun review(a: String, b: String, at: Double?) =
        "review?a=${Uri.encode(a)}&b=${Uri.encode(b)}" + (at?.let { "&at=$it" } ?: "")

    /** Lap analysis of one saved session (FBBD-102). */
    const val SessionAnalysisPattern = "sessions/{id}/analysis?corner={corner}"
    const val SessionIdArg = "id"

    /** Opened on one corner's passes (FBBD-93), as from the progression: its CornerId. */
    const val CornerArg = "corner"

    fun sessionAnalysis(id: String, corner: String? = null) =
        "sessions/$id/analysis" + (corner?.let { "?corner=${Uri.encode(it)}" } ?: "")

    /** One session's whole video with the data overlay (FBBD-341). */
    const val WatchVideoPattern = "sessions/{id}/video"

    fun watchVideo(id: String) = "sessions/$id/video"

    /** The cue review of one session (FBBD-99). */
    const val CueReviewPattern = "sessions/{id}/cues"

    fun cueReview(id: String) = "sessions/$id/cues"

    const val Track = "track"
    const val Tracks = "tracks"

    /** The track editor for one saved track (FBBD-56). */
    const val TrackEditorPattern = "tracks/{id}"
    const val TrackIdArg = "id"

    fun trackEditor(id: String) = "tracks/$id"

    /** Progression over every session at one track (FBBD-181). */
    const val TrackProgressionPattern = "tracks/{id}/progression"

    fun trackProgression(id: String) = "tracks/$id/progression"

    /** One day at one track (FBBD-315): its sessions' ids, comma separated, from the Sessions day heading. */
    const val TrackDayPattern = "trackday?ids={ids}"
    const val TrackDayIdsArg = "ids"

    fun trackDay(ids: List<String>) = "trackday?ids=${Uri.encode(ids.joinToString(","))}"

    /** One Settings topic's page (UI-137 B), from Settings and the first-run checklist. */
    const val SettingsTopicPattern = "settings/{topic}"
    const val TopicArg = "topic"

    fun settingsTopic(topic: String) = "settings/$topic"

    /** Destinations that show the bottom bar (UI-11 option A, UI-136 B). */
    val TopLevel = setOf(Drive, Sessions, Car, Settings)
}
