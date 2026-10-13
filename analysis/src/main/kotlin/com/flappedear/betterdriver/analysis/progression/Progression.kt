package com.flappedear.betterdriver.analysis.progression

import com.flappedear.betterdriver.analysis.coaching.CornerRecord
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.confidence.MinimumSupportingLaps
import com.flappedear.betterdriver.analysis.focus.median
import com.flappedear.betterdriver.analysis.segments.summarizeConsistency
import com.flappedear.betterdriver.timing.track.TrackRevision
import com.flappedear.betterdriver.timing.track.cornerIdIn
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

// Longitudinal progression (FBBD-181): how the driver's laps and corners move from session to
// session, track day to track day and month to month at one track. It is worked out from the
// coaching history's records (FBBD-180), one per analysed session, so every number is the one that
// session's own analysis gave: nothing is analysed again here. Corners are followed by their
// CornerId (FBBD-172), never by position or by a reference lap, and never across two revisions of
// the track's corners unless [CornerMapping] says which corner is which. Passes graded Low are never
// counted; a value is precise, and may be shown as a number, only when every pass behind it is High
// and its channel was measured in a known unit (FBBD-177).

/** How sessions are put together into one point of the progression. */
enum class ProgressionGrouping {
    /** One point per session. */
    Session,

    /** One point per track day: the sessions driven on one calendar day, in the phone's time zone. */
    TrackDay,

    /** One point per calendar month. */
    Month,
}

/**
 * Which sessions take part: those in [sessionIds] (all when null), driven on one of [days] (any
 * when null), from [from] to [to] inclusive (open at a null end), days in the zone the progression
 * is worked out in.
 */
data class ProgressionFilter(
    val sessionIds: Set<String>? = null,
    val days: Set<LocalDate>? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
) {
    fun accepts(record: SessionCoachingRecord, zone: ZoneId): Boolean {
        val day = dayOf(record, zone)
        return (sessionIds == null || record.sessionId in sessionIds) &&
            (days == null || day in days) &&
            (from == null || !day.isBefore(from)) &&
            (to == null || !day.isAfter(to))
    }
}

/** The day [record]'s session was driven, in [zone]. */
fun dayOf(record: SessionCoachingRecord, zone: ZoneId): LocalDate =
    Instant.ofEpochMilli(record.sessionStartUtcMillis).atZone(zone).toLocalDate()

/**
 * Which corner of another revision of the track's corners is which (FBBD-179 maps them when the
 * corners are edited): the CornerId in revision [toRevision] of corner [cornerId] of revision
 * [fromRevision], or null when it has none there. Without a mapping, a corner is only itself in its
 * own revision: progressions never compare corners of two revisions.
 */
fun interface CornerMapping {
    fun map(fromRevision: Int, cornerId: String, toRevision: Int): String?

    companion object {
        val SameRevisionOnly = CornerMapping { from, id, to -> id.takeIf { from == to } }

        /**
         * The steps between revisions of the track's corners as [identity] (the one stored at the track)
         * keeps them (FBBD-179): a corner the driver renamed, or left alone while correcting others,
         * is itself in every revision; without an identity, [SameRevisionOnly].
         */
        fun of(identity: TrackRevision?): CornerMapping =
            identity?.let { r -> CornerMapping { from, id, to -> r.cornerIdIn(from, id, to) } } ?: SameRevisionOnly
    }
}

/** What a metric is: its unit, and which way is better (null when neither is, as a braking point). */
enum class ProgressionMetric(val lowerIsBetter: Boolean?) {
    BestLap(true),
    MedianLap(true),

    /** The middle half of the counted lap times (interquartile range), as the summary's lap spread. */
    LapSpread(true),

    /** The standard deviation of the counted lap times: their variance, in seconds. */
    LapDeviation(true),
    CornerTime(true),

    /** Metres from the corner's start; more is later. */
    BrakingPoint(null),
    MinimumSpeed(false),
    ExitSpeed(false),

    /** Metres from the corner's start; less is earlier. */
    ThrottlePickup(null),

    /** The middle half of the corner times (interquartile range): how repeatable the corner is. */
    CornerSpread(true),
}

/**
 * One value of a metric at one point: [value] in [unit], from [count] laps or passes. [precise]
 * when it may be shown as a number (every pass High, its channel measured, its unit known).
 * [provenance] is a braking point's or pickup's `measured` or `inferred`, never the two mixed.
 */
data class MetricValue(
    val value: Double,
    val count: Int,
    val precise: Boolean = true,
    val unit: String = "",
    val provenance: String = "",
)

/**
 * Which way a metric moved across the points of a progression: [change] is the change from the
 * first point to the last along the least-squares line through them, in the metric's unit, over
 * [points] points. [direction] -1, 0 (steady: less than [ProgressionMetric]'s threshold) or +1.
 * [better] says whether that is the better way; null when the metric has none or it is steady.
 */
data class Trend(
    val change: Double,
    val direction: Int,
    val points: Int,
    val precise: Boolean,
    val better: Boolean?,
)

/** A metric at every point of a progression, null where a point has no value, and its [trend]. */
data class MetricSeries(
    val metric: ProgressionMetric,
    val values: List<MetricValue?>,
    val unit: String = "",
) {
    val trend: Trend? = trendOf(metric, values)
}

/**
 * One point of a progression: its sessions ([sessionIds], oldest first), the day the first was
 * driven ([day]) and, for each session imported from another app's file, where it came from
 * ([origins], by session id).
 */
data class ProgressionPoint(
    val key: String,
    val day: LocalDate,
    val startUtcMillis: Long,
    val sessionIds: List<String>,
    val origins: Map<String, String> = emptyMap(),
    val countedLaps: Int = 0,
) {
    val imported: Boolean get() = origins.isNotEmpty()
}

/**
 * One corner across a progression, by its [cornerId] in the revision the progression shows
 * ([TrackProgression.revision]), with every [ProgressionMetric] of a corner.
 */
data class CornerProgression(
    val cornerId: String,
    val name: String,
    val series: List<MetricSeries>,
) {
    fun of(metric: ProgressionMetric): MetricSeries? = series.firstOrNull { it.metric == metric }
}

/** One counted lap in driving order across a progression: its session, its number and its time. */
data class OrderedLap(val sessionId: String, val number: Int, val seconds: Double)

/**
 * The progression at one track: its [points] (oldest first) grouped by [grouping], the lap
 * metrics at each ([laps]), each corner of [revision] ([corners], in track order), and the
 * sessions left out of the corners because their corners are another revision's that cannot be
 * mapped onto it ([otherRevisionSessions]). [lapsInOrder] is every counted lap of every point, in
 * the order they were driven (FBBD-175): across one track day, how the lap times went from the
 * first lap of the morning to the last.
 */
data class TrackProgression(
    val trackId: String,
    val grouping: ProgressionGrouping,
    val points: List<ProgressionPoint>,
    val laps: List<MetricSeries>,
    val revision: Int?,
    val corners: List<CornerProgression>,
    val otherRevisionSessions: List<String> = emptyList(),
    val lapsInOrder: List<OrderedLap> = emptyList(),
) {
    val empty: Boolean get() = points.isEmpty()

    fun lap(metric: ProgressionMetric): MetricSeries? = laps.firstOrNull { it.metric == metric }

    /**
     * Which way the lap times went over [lapsInOrder], lap by lap, as a typical lap's trend is
     * judged (steady within [SteadyLapSeconds]); null below [MinimumTrendPoints] laps.
     */
    val lapsInOrderTrend: Trend?
        get() = trendOf(ProgressionMetric.MedianLap, lapsInOrder.map { MetricValue(it.seconds, 1, unit = "s") })
}

/**
 * The progression of the sessions in [records] (one track's coaching history) that [filter]
 * accepts, grouped by [grouping], days in [zone]. Corners are shown in the revision of the latest
 * session taking part; [mapping] says which corner of another revision is which.
 */
fun trackProgression(
    records: List<SessionCoachingRecord>,
    grouping: ProgressionGrouping = ProgressionGrouping.Session,
    filter: ProgressionFilter = ProgressionFilter(),
    zone: ZoneId = ZoneId.systemDefault(),
    mapping: CornerMapping = CornerMapping.SameRevisionOnly,
): TrackProgression {
    val kept = records.distinctBy { it.sessionId }.filter { filter.accepts(it, zone) }
        .sortedWith(compareBy({ it.sessionStartUtcMillis }, { it.sessionId }))
    val trackId = kept.firstOrNull()?.trackId.orEmpty()
    val groups = kept.filter { it.trackId == trackId }.groupBy { keyOf(it, grouping, zone) }.values.toList()
    val points = groups.map { g ->
        ProgressionPoint(
            key = keyOf(g.first(), grouping, zone),
            day = dayOf(g.first(), zone),
            startUtcMillis = g.first().sessionStartUtcMillis,
            sessionIds = g.map { it.sessionId },
            origins = g.mapNotNull { r -> r.origin?.let { r.sessionId to it } }.toMap(),
            countedLaps = g.sumOf { r -> r.laps.count { it.counted } },
        )
    }
    val ordered = groups.flatten().flatMap { r ->
        r.laps.filter { it.counted }.sortedBy { it.number }.map { OrderedLap(r.sessionId, it.number, it.seconds) }
    }
    val latest = groups.lastOrNull()?.last() ?: return TrackProgression(
        trackId, grouping, points, lapSeries(groups), null, emptyList(), lapsInOrder = ordered,
    )
    val revision = latest.identityRevision
    val corners = latest.corners.map { c ->
        cornerProgression(c, groups.map { g -> g.mapNotNull { r -> mapped(r, c.cornerId, revision, mapping) } })
    }
    val other = groups.flatten().filter { r -> !mapsOnto(r, latest, mapping) }.map { it.sessionId }
    return TrackProgression(trackId, grouping, points, lapSeries(groups), revision, corners, other, ordered)
}

private fun keyOf(r: SessionCoachingRecord, grouping: ProgressionGrouping, zone: ZoneId): String = when (grouping) {
    ProgressionGrouping.Session -> r.sessionId
    ProgressionGrouping.TrackDay -> dayOf(r, zone).toString()
    ProgressionGrouping.Month -> YearMonth.from(dayOf(r, zone)).toString()
}

/** Whether any of [r]'s corners is one of [latest]'s by [mapping]. */
private fun mapsOnto(r: SessionCoachingRecord, latest: SessionCoachingRecord, mapping: CornerMapping): Boolean =
    r.corners.any { c -> mapping.map(r.identityRevision, c.cornerId, latest.identityRevision) != null }

/** [r]'s corner that is [cornerId] of [revision], by [mapping]; null when it has none. */
private fun mapped(r: SessionCoachingRecord, cornerId: String, revision: Int, mapping: CornerMapping): CornerRecord? =
    r.corners.firstOrNull { mapping.map(r.identityRevision, it.cornerId, revision) == cornerId }

/** Best, median, spread and deviation of the counted laps at each point. */
private fun lapSeries(groups: List<List<SessionCoachingRecord>>): List<MetricSeries> {
    val laps = groups.map { g -> g.flatMap { r -> r.laps.filter { it.counted }.map { it.seconds } } }
    fun series(metric: ProgressionMetric, of: (List<Double>) -> Double?) = MetricSeries(
        metric,
        laps.map { l -> of(l)?.let { MetricValue(it, l.size, unit = "s") } },
        "s",
    )
    return listOf(
        series(ProgressionMetric.BestLap) { it.minOrNull() },
        series(ProgressionMetric.MedianLap) { if (it.isEmpty()) null else median(it) },
        series(ProgressionMetric.LapSpread) { spread(it) },
        series(ProgressionMetric.LapDeviation) { deviation(it) },
    )
}

/** The interquartile range of [values]; null below three, as the summary's lap spread. */
internal fun spread(values: List<Double>): Double? =
    summarizeConsistency(values).takeIf { it.available }?.interquartileRange

/** The sample standard deviation of [values]; null below two. */
internal fun deviation(values: List<Double>): Double? {
    if (values.size < MinimumSupportingLaps) return null
    val mean = values.average()
    return kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
}
