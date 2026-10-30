package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.RecorderEvent

// The soak (FBBD-46) and the VBO export (FBBD-14) in the session validation report (FBBD-183).
// Any drive of 29 min or more is a soak: its GNSS, video and jank values are
// judged against FBBD-46 as well. Arek accepted the 29-min drive of 2026-10-18 as the soak.

private const val SoakMinutes = 29L
private const val SoakMicros = SoakMinutes * 60 * 1_000_000L
private const val ExportMicrosPer30Min = 30 * 60 * 1_000_000L

/** What a soak checks: the report's own value for it, and what FBBD-46 asks. */
private val SoakParts = listOf(
    Triple("soak.gnss_rate", "gnss.rate", "GNSS rate >= 24 Hz"),
    Triple("soak.gnss_dropped", "gnss.dropped", "GNSS dropped samples <= 0.5 %"),
    Triple("soak.gnss_gap", "gnss.max_gap", "GNSS longest gap <= 200 ms, reconnects left out"),
    Triple("soak.video", "video.phone.coverage", "camera records the whole run"),
    Triple("soak.jank", "ui.jank", "UI jank < 5 % of frames"),
)

/** The soak section from the report's [others] sections; [durationMicros] is the session's length. */
internal fun soakSection(others: List<QualitySection>, durationMicros: Long): QualitySection {
    val byId = others.flatMap { it.metrics }.associateBy { it.id }
    val minutes = durationMicros / MicrosPerSecond / SecondsPerMinute
    val metrics = SoakParts.map { (id, from, what) ->
        val criterion = "$what (FBBD-46)"
        val source = byId[from]
        when {
            durationMicros < SoakMicros ->
                notMeasured(id, what, criterion, "the drive lasted $minutes min; a soak needs $SoakMinutes min or more")
            source == null || source.verdict == Verdict.NOT_MEASURED || source.verdict == Verdict.INFO ->
                notMeasured(id, what, criterion, "not measured in this drive: ${source?.how ?: "no value"}")
            else -> source.copy(
                id = id,
                label = "Soak: ${source.label}",
                criterion = criterion,
                keys = issueKeys(criterion),
            )
        }
    }
    val length = info("soak.minutes", "Drive length", minutes.toDouble(), "min", Basis.MEASURED, "session length")
    return QualitySection("Soak ($SoakMinutes min)", listOf(length) + metrics)
}

private const val SecondsPerMinute = 60L

private const val ExportLostCriterion = "0 samples lost in the VBO export (FBBD-14)"
private const val ExportTimeCriterion = "a 30 min session exports within 10 s (FBBD-14)"
private const val ExportMillisPer30Min = 10_000.0

/** The VBO exports of the session, as the app logged them ([EventKinds.VBO_EXPORT]). */
internal fun exportSection(events: List<RecorderEvent>, durationMicros: Long): QualitySection? {
    val export = events.ofKind(EventKinds.VBO_EXPORT).lastOrNull() ?: return null
    val f = fields(export.text)
    val rows = f["rows"]?.toLongOrNull()
    val samples = f["samples"]?.toLongOrNull()
    val ms = f["ms"]?.toLongOrNull()
    val limit = ExportMillisPer30Min * maxOf(1.0, durationMicros.toDouble() / ExportMicrosPer30Min)
    val metrics = listOfNotNull(
        if (rows != null && samples != null) {
            val lost = (samples - rows).coerceAtLeast(0)
            judged(
                "export.lost", "Samples lost in VBO export", lost.toDouble(), "", lost == 0L, Basis.MEASURED,
                ExportLostCriterion, "$samples GNSS samples with a position, $rows VBO data rows",
            )
        } else {
            null
        },
        ms?.let {
            judged(
                "export.time", "VBO export time", it / MillisPerSecond, "s", it <= limit, Basis.MEASURED,
                ExportTimeCriterion,
                "time to write the VBO on the phone; limit ${(limit / MillisPerSecond).toLong()} s " +
                    "for this session's length (10 s per 30 min)",
            )
        },
    )
    return QualitySection("Export", metrics)
}

private const val MillisPerSecond = 1_000.0
