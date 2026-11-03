package com.flappedear.betterdriver.core.ble

// The rows of a live device page: RaceBox diagnostics (FBBD-89, FBBD-371) and the Equipment pages'
// live data (FBBD-440). In :recorder so every device module can make them; the package stays the
// RaceBox one so the Android and iOS code that names them is unchanged.

/** One value on a live device page. */
data class DiagRow(val label: String, val value: String)

/**
 * A titled group of values (UI-39 option A: one live list). [note] is dim text under the rows;
 * [action] is the label of a button under them (calibration only), enabled when [actionEnabled].
 */
data class DiagSection(
    val title: String,
    val rows: List<DiagRow>,
    val note: String? = null,
    val action: String? = null,
    val actionEnabled: Boolean = false,
)

/** The top card's text: [ok] is a green dot, otherwise amber; a line and a detail line. */
data class DiagHeaderText(val ok: Boolean, val title: String, val detail: String)
