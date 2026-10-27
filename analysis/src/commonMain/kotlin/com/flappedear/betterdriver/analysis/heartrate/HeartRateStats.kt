package com.flappedear.betterdriver.analysis.heartrate

import com.flappedear.betterdriver.analysis.data.SamplePoint
import com.flappedear.betterdriver.analysis.data.TelemetryChannel
import com.flappedear.betterdriver.analysis.data.TelemetrySession
import com.flappedear.betterdriver.analysis.laps.TimedLap
import com.flappedear.betterdriver.analysis.vehicle.bucketedSegments
import com.flappedear.betterdriver.recorder.log.HeartRateLogIds
import kotlin.math.roundToInt

/**
 * The heart rate of a stretch of a session (FBBD-225): the average, highest and lowest of the
 * beats per minute the strap sent in it, the session times of the first and last, and how long the
 * strap was actually sending: [coveredSeconds] leaves out gaps over [MaxBeatGapSeconds], such as a
 * strap that dropped and came back. Only what was recorded: a stretch without heart rate has no
 * stats, never a zero (UI-109).
 */
data class HeartRateStats(
    val averageBpm: Int,
    val maxBpm: Int,
    val minBpm: Int,
    val samples: Int,
    val firstSeconds: Double,
    val lastSeconds: Double,
    val coveredSeconds: Double,
)

/** The longest wait between two beats that still counts as the strap sending, as the drive check's hr.gap. */
const val MaxBeatGapSeconds = 5.0

/** The heart rate of [session] from [from] to [to] seconds, both included; null without any there. */
@Suppress("ReturnCount") // No channel, or nothing recorded in the stretch.
fun heartRateStats(
    session: TelemetrySession,
    from: Double = Double.NEGATIVE_INFINITY,
    to: Double = Double.POSITIVE_INFINITY,
): HeartRateStats? {
    val channel = heartRateChannel(session) ?: return null
    val recorded = channel.timestamps.indices
        .filter { channel.timestamps[it] in from..to && isBeat(channel.values[it]) }
    if (recorded.isEmpty()) return null
    val bpm = recorded.map { channel.values[it] }
    val covered = recorded.zipWithNext { a, b -> channel.timestamps[b] - channel.timestamps[a] }
        .filter { it <= MaxBeatGapSeconds }
        .sum()
    return HeartRateStats(
        averageBpm = bpm.average().roundToInt(),
        maxBpm = bpm.max().roundToInt(),
        minBpm = bpm.min().roundToInt(),
        samples = bpm.size,
        firstSeconds = channel.timestamps[recorded.first()],
        lastSeconds = channel.timestamps[recorded.last()],
        coveredSeconds = covered,
    )
}

/** A heart rate the strap sent: a gap or a zero is none. */
private fun isBeat(bpm: Double) = bpm.isFinite() && bpm > 0.0

/** RaceChrono's export of a strap's heart rate ("heart_rate-hrm"), FBBD-176. */
private const val RaceChronoName = "heart_rate-hrm"

/**
 * The heart rate channel of [session]: the app's own ([HeartRateLogIds.NAME]) or, in an imported
 * RaceChrono session, its "heart_rate-hrm" column; null without one.
 */
fun heartRateChannel(session: TelemetrySession): TelemetryChannel? =
    session.channel(HeartRateLogIds.NAME) ?: session.channel(RaceChronoName)

/** How long each point of the heart rate chart averages (UI-160 A). */
const val HeartRateChartStepSeconds = 10.0

/**
 * The heart rate of [session] for a chart over the whole session (UI-160 A): [HeartRateChartStepSeconds]
 * averages, split where the strap sent nothing for over [MaxBeatGapSeconds] so a dropout stays blank.
 * Empty without heart rate.
 */
fun heartRateSeries(session: TelemetrySession): List<List<SamplePoint>> {
    val channel = heartRateChannel(session)?.takeIf { it.sampleCount > 0 } ?: return emptyList()
    return bucketedSegments(
        channel,
        channel.timestamps.first(),
        channel.timestamps.last(),
        HeartRateChartStepSeconds,
        MaxBeatGapSeconds,
        ::isBeat,
    )
}

/** The heart rate of each of [laps] in [session], in their order; null for a lap without any. */
fun lapHeartRates(session: TelemetrySession, laps: List<TimedLap>): List<HeartRateStats?> =
    laps.map { heartRateStats(session, it.startTelemetryTime, it.endTelemetryTime) }
