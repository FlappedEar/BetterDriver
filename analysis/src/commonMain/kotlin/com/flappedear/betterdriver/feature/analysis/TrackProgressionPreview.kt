@file:Suppress("MagicNumber") // Example data.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.coaching.AskedChange
import com.flappedear.betterdriver.analysis.coaching.CornerPassRecord
import com.flappedear.betterdriver.analysis.coaching.CornerRecord
import com.flappedear.betterdriver.analysis.coaching.FocusAsk
import com.flappedear.betterdriver.analysis.coaching.LapRecord
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.ProgressionFilter
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.coachingProgression
import com.flappedear.betterdriver.analysis.progression.trackProgression

/**
 * Four example sessions at one track on three days, the driver quicker each time, the third
 * imported from a VBO file, Corner 2 the focus of the first: the progression screen's preview and
 * layout tests, on Android and iOS.
 */
val PreviewProgressionRecords: List<SessionCoachingRecord> by lazy {
    val day = ProgressionDay.of(2026, 9, 5)
    fun at(days: Long, hour: Int) = day.plusDays(days).epochDay * 86_400_000L + hour * 3_600_000L
    fun corners(k: Int, grade: String = "HIGH") = (1..4).map { c ->
        CornerRecord(
            cornerId = "c$c",
            name = "Corner $c",
            speedUnit = "km/h",
            speedMeasured = true,
            brakeMeasured = c != 3,
            passes = (1..5).map { lap ->
                CornerPassRecord(
                    lap = lap,
                    seconds = 6.0 + c - k * 0.08 * (if (c == 2) 2 else 1) + lap * 0.02,
                    confidence = if (c == 4 && lap == 2) "MEDIUM" else grade,
                    brakingMeters = -40.0 + k * (if (c == 2) 3.0 else 1.0) + lap,
                    brakingProvenance = if (c == 3) "inferred" else "measured",
                    minimumSpeed = 70.0 + c * 5 + k * 2.0,
                    exitSpeed = 95.0 + c * 4 + k * 1.5,
                )
            },
        )
    }
    fun record(id: String, start: Long, k: Int, origin: String? = null, coachedFrom: String? = null) =
        SessionCoachingRecord(
            trackId = "preview",
            sessionId = id,
            sessionStartUtcMillis = start,
            identityRevision = 1,
            coachedFrom = coachedFrom,
            focus = if (k == 0) {
                listOf(FocusAsk("c2", "Corner 2", "primary", "HIGH", listOf(AskedChange("brakingPoint", 1))))
            } else {
                emptyList()
            },
            corners = corners(k),
            laps = (1..6).map { LapRecord(it, 92.4 - k * 0.6 + it * 0.15, counted = it != 6) },
            origin = origin,
        )
    listOf(
        record("p0", at(0, 10), 0),
        record("p1", at(0, 15), 1, coachedFrom = "p0"),
        record("p2", at(14, 11), 2, origin = "VBO lap-12.vbo"),
        record("p3", at(30, 10), 3),
    )
}

/** The progression screen with [PreviewProgressionRecords], grouped by [grouping]. */
fun previewProgressionUi(grouping: ProgressionGrouping = ProgressionGrouping.Session): ProgressionUi {
    val zone = DayZone.Utc
    val records = PreviewProgressionRecords
    val p = trackProgression(records, grouping, ProgressionFilter(), zone)
    val ui = progressionUi(
        "Sim Raceway",
        p,
        p,
        coachingProgression(p, records),
        emptySet(),
        ProgressionPeriod.All,
        null,
        zone,
    )
    return ui.copy(corners = ui.corners.map { it.copy(sessionId = latestSessionWith(records, p, it.cornerId)) })
}
