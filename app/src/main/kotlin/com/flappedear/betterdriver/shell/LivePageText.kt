package com.flappedear.betterdriver.shell

/**
 * What differs between the RaceBox diagnostics page and an Equipment page's live data (FBBD-440),
 * which share its look: the title, the Bluetooth notice, the recording card and the closing note.
 */
data class LivePageText(
    val title: String,
    val permission: String,
    val recording: String,
    val recordingNote: String,
    val note: String,
)

/** The RaceBox diagnostics page's own text. */
val RaceBoxPageText = LivePageText(
    title = RaceBoxDiagnosticsTitle,
    permission = "Allow Nearby devices for BetterDriver in Android settings to connect to the RaceBox.",
    recording = "Recording. Showing what the recorder receives.",
    recordingNote = "Everything else is shown when no session is recording.",
    note = DiagNotReported,
)
