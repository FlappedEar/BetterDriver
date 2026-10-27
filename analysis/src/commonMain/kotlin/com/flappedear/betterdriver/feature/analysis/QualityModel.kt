package com.flappedear.betterdriver.feature.analysis

/**
 * A failed check on the Data quality card: [value] with its unit, and the FBBD [keys] of its
 * criterion. [derived] marks a value worked out by a heuristic or an estimate, not read straight
 * from the data.
 */
data class QualityRow(
    val section: String,
    val label: String,
    val value: String,
    val keys: String,
    val derived: Boolean,
)

/**
 * The Data quality card (FBBD-183): how many of the session's checks passed, failed or could not be
 * measured, with the failures listed. Every check, with how its value was found, is in the session's
 * quality.json and in the drive's log.
 */
data class QualityUi(val passed: Int, val failed: Int, val notMeasured: Int, val failures: List<QualityRow>)
