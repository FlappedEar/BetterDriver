@file:Suppress("TooManyFunctions") // One screen's words, one small function each.

package com.flappedear.betterdriver.feature.analysis

import com.flappedear.betterdriver.analysis.coaching.FocusVerdict
import com.flappedear.betterdriver.analysis.coaching.SessionCoachingRecord
import com.flappedear.betterdriver.analysis.progression.CoachingStep
import com.flappedear.betterdriver.analysis.progression.CornerMapping
import com.flappedear.betterdriver.analysis.progression.DayZone
import com.flappedear.betterdriver.analysis.progression.MetricSeries
import com.flappedear.betterdriver.analysis.progression.MinimumTrendPoints
import com.flappedear.betterdriver.analysis.progression.ProgressionFilter
import com.flappedear.betterdriver.analysis.progression.ProgressionGrouping
import com.flappedear.betterdriver.analysis.progression.ProgressionMetric
import com.flappedear.betterdriver.analysis.progression.ProgressionDay
import com.flappedear.betterdriver.analysis.progression.ProgressionPoint
import com.flappedear.betterdriver.analysis.progression.TrackProgression
import com.flappedear.betterdriver.analysis.progression.coachingProgression
import com.flappedear.betterdriver.analysis.progression.dayLabel
import com.flappedear.betterdriver.analysis.progression.monthLabel
import com.flappedear.betterdriver.analysis.progression.sessionTimeLabel
import com.flappedear.betterdriver.analysis.progression.trackProgression
import com.flappedear.betterdriver.format.formatLapTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// Common Kotlin (Android and iOS): the progression at a track (FBBD-181) as its screen shows it:
// each lap metric and each corner, point by point, with the way it is going in words. A number is
// said only where the value is precise (every pass behind it graded High, its channel measured in a
// known unit, FBBD-177); elsewhere the same words without it. Lap times come from the start/finish
// line and are always said.

/** Which sessions the progression covers, counted back from today. */
enum class ProgressionPeriod(val label: String, val days: Long?) {
    All("All", null),
    Quarter("90 days", QuarterDays),
    Year("12 months", YearDays),
}

private const val QuarterDays = 90L
private const val YearDays = 365L
private const val MillisPerSecond = 1000.0

/** How a trend reads: the better way, the worse way, a change neither better nor worse, or steady. */
enum class TrendTone { Better, Worse, Neutral, Steady, Unknown }

/**
 * One metric across the points: [label], its latest value ([latest], empty when it may not be
 * said as a number), its trend in words, and its [values] for the small chart (null where a point
 * has none). [doubtful] when the latest value or the trend is not precise.
 */
data class MetricRow(
    val label: String,
    val latest: String,
    val trend: String,
    val tone: TrendTone,
    val values: List<Double?>,
    val doubtful: Boolean = false,
    /** True when lower values are drawn higher, so up is better on the chart (times). */
    val invert: Boolean = false,
)

/** One corner: its time at each point, and the other metrics under it. */
data class CornerRow(
    val cornerId: String,
    val name: String,
    val time: MetricRow,
    val details: List<MetricRow>,
    /**
     * The latest session taking part whose corners are the progression's revision and that drove
     * this corner: where its passes, traces and video open (FBBD-93); null when none.
     */
    val sessionId: String? = null,
)

/**
 * One point of the progression in the list: a session, a track day or a month. [best] its best
 * lap; [origin] where its imported sessions came from ("Imported · VBO lap-12.vbo"); [excluded]
 * when the driver left the session out; [day] the day to open when the point is a track day.
 */
data class PointRow(
    val key: String,
    val label: String,
    val best: String,
    val detail: String,
    val origin: String? = null,
    val excluded: Boolean = false,
    val day: ProgressionDay? = null,
)

/** What came of the coaching at one point, in words. */
data class CoachingStepRow(val label: String, val text: String, val tone: TrendTone)

/** The progression screen. */
data class ProgressionUi(
    val title: String,
    val grouping: ProgressionGrouping,
    val period: ProgressionPeriod,
    val day: ProgressionDay?,
    val summary: String,
    val points: List<PointRow>,
    val laps: List<MetricRow>,
    val corners: List<CornerRow>,
    val coaching: List<CoachingStepRow>,
    val coachingSummary: String? = null,
    val notes: List<String> = emptyList(),
    /** Sessions at the track still being analysed for it, shown while they are. */
    val adding: Int = 0,
    /**
     * With one track day open ([day]), every counted lap of it in the order driven (FBBD-175): how
     * the lap times went from the first lap of the day to the last. Null for every other view.
     */
    val dayLaps: MetricRow? = null,
) {
    val empty: Boolean get() = points.isEmpty()
}

/**
 * What the driver chose on the progression screen: how sessions are grouped, the [period] counted
 * back from today, one [day] to show alone (null for every day in the period), and the sessions
 * left out ([excluded]).
 */
data class ProgressionChoice(
    val grouping: ProgressionGrouping = ProgressionGrouping.Session,
    val period: ProgressionPeriod = ProgressionPeriod.All,
    val day: ProgressionDay? = null,
    val excluded: Set<String> = emptySet(),
)

/**
 * The progression screen of track [title] from its coaching history [records] (FBBD-180) as the
 * driver [choice]s it, [today] in [zone]. [mapping] says which corner of another revision of the
 * track's corners is which (none without it).
 */
@Suppress("LongParameterList") // The records and every choice that filters them.
fun progressionScreenUi(
    title: String,
    records: List<SessionCoachingRecord>,
    choice: ProgressionChoice,
    today: ProgressionDay,
    zone: DayZone,
    mapping: CornerMapping = CornerMapping.SameRevisionOnly,
): ProgressionUi {
    val from = choice.period.days?.let { today.minusDays(it) }
    val base = if (choice.day != null) ProgressionFilter(days = setOf(choice.day)) else ProgressionFilter(from = from)
    val listed = trackProgression(records, choice.grouping, base, zone, mapping)
    val counted = listed.points.flatMap { it.sessionIds }.filter { it !in choice.excluded }.toSet()
    val shown = trackProgression(records, choice.grouping, base.copy(sessionIds = counted), zone, mapping)
    val ui = progressionUi(
        title, shown, listed, coachingProgression(shown, records), choice.excluded, choice.period, choice.day, zone,
    )
    return ui.copy(corners = ui.corners.map { it.copy(sessionId = latestSessionWith(records, shown, it.cornerId)) })
}

/**
 * The latest session of [progression] (of [records]) that drove corner [cornerId] in the revision
 * the progression shows its corners in: a CornerId of another revision is another corner (FBBD-172),
 * so a session of another revision never opens for it. Null when none.
 */
fun latestSessionWith(records: List<SessionCoachingRecord>, progression: TrackProgression, cornerId: String): String? {
    val revision = progression.revision ?: return null
    val taking = progression.points.flatMap { it.sessionIds }.toSet()
    return records
        .filter { it.sessionId in taking && it.identityRevision == revision && it.corner(cornerId) != null }
        .maxByOrNull { it.sessionStartUtcMillis }
        ?.sessionId
}

/**
 * The screen of [progression] (sessions the driver left out taken away) and [listed] (the same
 * without leaving any out: every point the list shows, [excluded] marked), with its [coaching]
 * steps, days in [zone].
 */
@Suppress("LongParameterList") // What the screen shows and the choices that made it.
fun progressionUi(
    title: String,
    progression: TrackProgression,
    listed: TrackProgression,
    coaching: List<CoachingStep>,
    excluded: Set<String>,
    period: ProgressionPeriod,
    day: ProgressionDay?,
    zone: DayZone,
): ProgressionUi {
    val n = progression.points.size
    val word = pointWord(progression.grouping)
    val notes = buildList {
        val other = progression.otherRevisionSessions.size
        if (other > 0) {
            add(
                "${plural(other, "session")} from before the track's corners were changed " +
                    "${if (other == 1) "is" else "are"} left out of the corners: they cannot be compared.",
            )
        }
        if (n in 1 until MinimumTrendPoints) add("A trend needs ${plural(MinimumTrendPoints, word)}.")
    }
    return ProgressionUi(
        title = title,
        grouping = progression.grouping,
        period = period,
        day = day,
        summary = summary(listed, progression, excluded),
        points = listed.points.map { pointRow(it, listed, excluded, zone) }.reversed(),
        laps = progression.laps.map { metricRow(it, progression.grouping) },
        corners = progression.corners.map { c ->
            CornerRow(
                c.cornerId,
                c.name,
                metricRow(c.of(ProgressionMetric.CornerTime)!!, progression.grouping),
                c.series.filter { it.metric != ProgressionMetric.CornerTime }
                    .filter { s -> s.values.any { it != null } }
                    .map { metricRow(it, progression.grouping) },
            )
        },
        coaching = coaching.zip(progression.points).filter { !it.first.empty }
            .map { (step, p) -> coachingRow(step, label(p, progression.grouping, zone)) }.reversed(),
        coachingSummary = coachingSummary(coaching),
        notes = notes,
        dayLaps = day?.let { dayLapsRow(progression) },
    )
}

/** The label of [ProgressionUi.dayLaps]. */
const val DayLapsLabel = "Every lap of the day, in order"

/** Every counted lap of [progression] in driving order, with the way the lap times went over them. */
internal fun dayLapsRow(progression: TrackProgression): MetricRow? {
    val laps = progression.lapsInOrder
    if (laps.isEmpty()) return null
    val t = progression.lapsInOrderTrend
    val over = t?.let { " over ${plural(it.points, "lap")}" }.orEmpty()
    val (text, tone) = when {
        t == null -> "Too few laps for a trend" to TrendTone.Unknown
        t.direction == 0 -> "Steady$over" to TrendTone.Steady
        t.better == true -> "${seconds(abs(t.change))} s quicker$over" to TrendTone.Better
        else -> "${seconds(abs(t.change))} s slower$over" to TrendTone.Worse
    }
    return MetricRow(DayLapsLabel, lapTime(laps.last().seconds), text, tone, laps.map { it.seconds }, invert = true)
}

private fun summary(listed: TrackProgression, shown: TrackProgression, excluded: Set<String>): String {
    val sessions = listed.points.flatMap { it.sessionIds }
    val days = listed.points.map { it.day }.distinct().size
    val imported = listed.points.sumOf { it.origins.size }
    val left = sessions.count { it in excluded }
    return buildList {
        add("${plural(sessions.size, "session")} on ${plural(days, "day")}")
        if (imported > 0) add("$imported imported")
        if (left > 0) add("$left left out")
        if (shown.points.isEmpty() && sessions.isNotEmpty()) add("none counted")
    }.joinToString(" · ")
}

/** "12 Oct 2026, 10:15" for a session, "12 Oct 2026" for a track day, "October 2026" for a month. */
internal fun label(p: ProgressionPoint, grouping: ProgressionGrouping, zone: DayZone): String = when (grouping) {
    ProgressionGrouping.Session -> sessionTimeLabel(p.startUtcMillis, zone)
    ProgressionGrouping.TrackDay -> p.day.dayLabel()
    ProgressionGrouping.Month -> p.day.monthLabel()
}

private fun pointRow(p: ProgressionPoint, listed: TrackProgression, excluded: Set<String>, zone: DayZone): PointRow {
    val i = listed.points.indexOf(p)
    val best = listed.lap(ProgressionMetric.BestLap)?.values?.getOrNull(i)?.value
    val sessions = if (listed.grouping == ProgressionGrouping.Session) {
        ""
    } else {
        "${plural(p.sessionIds.size, "session")} · "
    }
    val origin = when {
        p.origins.isEmpty() -> null
        listed.grouping == ProgressionGrouping.Session -> "Imported · ${p.origins.values.single()}"
        else -> "${p.origins.size} imported · ${p.origins.values.distinct().joinToString(", ")}"
    }
    return PointRow(
        key = p.key,
        label = label(p, listed.grouping, zone),
        best = best?.let(::lapTime) ?: Missing,
        detail = sessions + plural(p.countedLaps, "counted lap"),
        origin = origin,
        excluded = listed.grouping == ProgressionGrouping.Session && p.sessionIds.all { it in excluded },
        day = p.day.takeIf { listed.grouping == ProgressionGrouping.TrackDay },
    )
}

private fun lapTime(seconds: Double) = formatLapTime((seconds * MillisPerSecond).roundToLong())

private val Labels = mapOf(
    ProgressionMetric.BestLap to "Best lap",
    ProgressionMetric.MedianLap to "Typical lap (median)",
    ProgressionMetric.LapSpread to "Lap spread (middle half)",
    ProgressionMetric.LapDeviation to "Lap variation (std. dev.)",
    ProgressionMetric.CornerTime to "Median corner time",
    ProgressionMetric.BrakingPoint to "Braking point",
    ProgressionMetric.MinimumSpeed to "Minimum speed",
    ProgressionMetric.ExitSpeed to "Exit speed",
    ProgressionMetric.ThrottlePickup to "Throttle pickup",
    ProgressionMetric.CornerSpread to "Repeatability (spread)",
)

/** [s] as a row: its latest value and its trend over the points, numbers only where precise. */
internal fun metricRow(s: MetricSeries, grouping: ProgressionGrouping): MetricRow {
    val last = s.values.lastOrNull { it != null }
    val latest = when {
        last == null -> Missing
        !last.precise -> ""
        else -> value(s.metric, last.value, last.unit)
    }
    val t = s.trend
    val word = pointWord(grouping)
    val over = t?.let { " over ${plural(it.points, word)}" }.orEmpty()
    val (text, tone) = when {
        t == null -> "Too few ${word}s for a trend" to TrendTone.Unknown
        t.direction == 0 -> "Steady$over" to TrendTone.Steady
        else -> {
            val tone = when (t.better) {
                true -> TrendTone.Better
                false -> TrendTone.Worse
                null -> TrendTone.Neutral
            }
            val amount = if (t.precise) change(s.metric, abs(t.change), s.unit) else ""
            (amount + way(s.metric, t.change > 0)).replaceFirstChar { it.uppercase() } + over to tone
        }
    }
    val doubtful = last?.precise == false || t?.precise == false
    val invert = s.metric.lowerIsBetter == true
    return MetricRow(Labels.getValue(s.metric), latest, text, tone, s.values.map { it?.value }, doubtful, invert)
}

/** A value of [metric] as shown: a lap time, seconds, metres from the corner's start, or a speed. */
private fun value(metric: ProgressionMetric, v: Double, unit: String): String = when (metric) {
    ProgressionMetric.BestLap, ProgressionMetric.MedianLap -> lapTime(v)
    ProgressionMetric.LapSpread, ProgressionMetric.LapDeviation,
    ProgressionMetric.CornerTime, ProgressionMetric.CornerSpread,
    -> "${seconds(v)} s"
    ProgressionMetric.BrakingPoint, ProgressionMetric.ThrottlePickup -> {
        val m = v.roundToInt()
        when {
            m < 0 -> "${-m} m before the corner"
            m == 0 -> "at the corner's start"
            else -> "$m m into the corner"
        }
    }
    ProgressionMetric.MinimumSpeed, ProgressionMetric.ExitSpeed -> "${v.roundToInt()} $unit"
}

/** "0.84 s ", "6 m ", "4 km/h ": the size of a change, before its direction. */
private fun change(metric: ProgressionMetric, size: Double, unit: String): String = when (metric) {
    ProgressionMetric.BrakingPoint, ProgressionMetric.ThrottlePickup -> "${max(1, size.roundToInt())} m "
    ProgressionMetric.MinimumSpeed, ProgressionMetric.ExitSpeed -> "${max(1, size.roundToInt())} $unit "
    else -> "${seconds(size)} s "
}

/** Which way [metric] went, [up] when its value rose. */
private fun way(metric: ProgressionMetric, up: Boolean): String = when (metric) {
    ProgressionMetric.BestLap, ProgressionMetric.MedianLap, ProgressionMetric.CornerTime ->
        if (up) "slower" else "quicker"
    ProgressionMetric.LapSpread, ProgressionMetric.LapDeviation, ProgressionMetric.CornerSpread ->
        if (up) "less steady" else "steadier"
    ProgressionMetric.BrakingPoint, ProgressionMetric.ThrottlePickup -> if (up) "later" else "earlier"
    ProgressionMetric.MinimumSpeed, ProgressionMetric.ExitSpeed -> if (up) "more speed" else "less speed"
}

/** What one point is: a session, a track day or a month. */
private fun pointWord(grouping: ProgressionGrouping): String = when (grouping) {
    ProgressionGrouping.Session -> "session"
    ProgressionGrouping.TrackDay -> "track day"
    ProgressionGrouping.Month -> "month"
}

private fun coachingRow(step: CoachingStep, label: String): CoachingStepRow {
    val worked = step.attempts.map { a ->
        val what = when (a.verdict) {
            FocusVerdict.Improved -> "improved"
            FocusVerdict.NoGain -> "no gain yet"
            FocusVerdict.TooFewPasses, FocusVerdict.NotDriven -> "too few passes to tell"
            FocusVerdict.NotComparable -> "corners changed since"
        }
        "${a.ask.name}: $what"
    }
    val held = buildList {
        if (step.held > 0) add("${plural(step.held, "earlier gain")} held")
        if (step.partlyHeld > 0) add("${step.partlyHeld} partly held")
        if (step.lost > 0) add("${step.lost} lost")
    }
    val tone = when {
        step.lost > 0 && step.improved == 0 -> TrendTone.Worse
        step.improved > 0 || step.held > 0 -> TrendTone.Better
        else -> TrendTone.Steady
    }
    return CoachingStepRow(label, (worked + held).joinToString(" · "), tone)
}

/** "2 of 3 focuses improved their corner; 1 gain held later." Counts, never advice. */
private fun coachingSummary(steps: List<CoachingStep>): String? {
    val measured = steps.sumOf { it.measured }
    if (measured == 0 && steps.all { it.checks.isEmpty() }) return null
    val improved = steps.sumOf { it.improved }
    val held = steps.sumOf { it.held }
    val lost = steps.sumOf { it.lost }
    val first = "$improved of ${plural(measured, "focus")} improved ${if (measured == 1) "its" else "their"} corner"
    val later = if (held + lost == 0) "" else "; ${plural(held, "gain")} held later, $lost lost"
    return "$first$later."
}

private fun plural(n: Int, word: String) = when {
    n == 1 -> "1 $word"
    word.endsWith("s") -> "$n ${word}es"
    else -> "$n ${word}s"
}
