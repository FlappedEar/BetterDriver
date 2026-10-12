package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToLong

/**
 * The GNSS samples of one whole second of a recording, as the continuous log holds them (FBBD-183):
 * enough to work out the sample rate, dropped samples, gaps, position noise while parked and the
 * phone clock against the receiver's, without the session file.
 *
 * [second] counts from the session's start; [tMicros] is the first sample's session time. [samples]
 * GNSS sets arrived, [fixes] of them with a position. [missing] samples are missing from the
 * receiver's fix times, each step longer than the usual one ([stepMillis]) hiding round(step / usual)
 * - 1; [longGaps] steps were over 200 ms; [maxRxGapMillis] is the longest step on the receiver's clock
 * ending in this second. Steps across a link drop ([afterDrop]) count in none of these.
 * [maxGapMicros] is the longest wait on the phone between fixes ending in this second, link drops
 * included: what the track screen's GNSS banner goes by; [firstFixMicros] is when its first fix
 * came, which ends any wait over a second. [lagMicros] is the smallest
 * of (session time − receiver time since the session's start): how far the phone's clock is from the
 * receiver's, plus the shortest delivery delay. [lat], [lon] are the mean fix, [varianceM2] the mean
 * squared distance of the fixes from it; [kmh] the mean speed, [kmhMin] and [kmhMax] its range.
 */
data class GnssSecond(
    val second: Long,
    val tMicros: Long,
    val samples: Int,
    val fixes: Int,
    val missing: Int,
    val stepMillis: Long?,
    val longGaps: Int,
    val maxRxGapMillis: Long?,
    val maxGapMicros: Long?,
    val afterDrop: Boolean,
    val utcFirst: Long?,
    val utcLast: Long?,
    val lagMicros: Long?,
    val lat: Double?,
    val lon: Double?,
    val varianceM2: Double?,
    val kmh: Double?,
    val kmhMin: Double?,
    val kmhMax: Double?,
    val firstFixMicros: Long? = null,
) {
    fun format(): String = buildString {
        append("sec=").append(second).append(" t_us=").append(tMicros)
        append(" n=").append(samples).append(" fix=").append(fixes).append(" miss=").append(missing)
        stepMillis?.let { append(" step_ms=").append(it) }
        append(" long=").append(longGaps)
        maxRxGapMillis?.let { append(" max_rx_ms=").append(it) }
        maxGapMicros?.let { append(" max_us=").append(it) }
        if (afterDrop) append(" drop=1")
        utcFirst?.let { append(" utc0=").append(it) }
        utcLast?.let { append(" utc1=").append(it) }
        lagMicros?.let { append(" lag_us=").append(it) }
        lat?.let { append(" lat=").append(String.format(Locale.ROOT, "%.7f", it)) }
        lon?.let { append(" lon=").append(String.format(Locale.ROOT, "%.7f", it)) }
        varianceM2?.let { append(" var_m2=").append(String.format(Locale.ROOT, "%.4f", it)) }
        kmh?.let { append(" kmh=").append(String.format(Locale.ROOT, "%.2f", it)) }
        kmhMin?.let { append(" kmh_min=").append(String.format(Locale.ROOT, "%.2f", it)) }
        kmhMax?.let { append(" kmh_max=").append(String.format(Locale.ROOT, "%.2f", it)) }
        firstFixMicros?.let { append(" fix_us=").append(it) }
    }

    companion object {
        /** A [CheckLog.GNSS] line's message read back; null when it is not one. */
        fun parse(message: String): GnssSecond? {
            val f = CheckLog.fields(message)
            val second = f["sec"]?.toLongOrNull()
            val t = f["t_us"]?.toLongOrNull()
            val n = f["n"]?.toIntOrNull()
            if (second == null || t == null || n == null) return null
            return GnssSecond(
                second = second,
                tMicros = t,
                samples = n,
                fixes = f["fix"]?.toIntOrNull() ?: 0,
                missing = f["miss"]?.toIntOrNull() ?: 0,
                stepMillis = f["step_ms"]?.toLongOrNull(),
                longGaps = f["long"]?.toIntOrNull() ?: 0,
                maxRxGapMillis = f["max_rx_ms"]?.toLongOrNull(),
                maxGapMicros = f["max_us"]?.toLongOrNull(),
                afterDrop = f["drop"] == "1",
                utcFirst = f["utc0"]?.toLongOrNull(),
                utcLast = f["utc1"]?.toLongOrNull(),
                lagMicros = f["lag_us"]?.toLongOrNull(),
                lat = f["lat"]?.toDoubleOrNull(),
                lon = f["lon"]?.toDoubleOrNull(),
                varianceM2 = f["var_m2"]?.toDoubleOrNull(),
                kmh = f["kmh"]?.toDoubleOrNull(),
                kmhMin = f["kmh_min"]?.toDoubleOrNull(),
                kmhMax = f["kmh_max"]?.toDoubleOrNull(),
                firstFixMicros = f["fix_us"]?.toLongOrNull(),
            )
        }
    }
}

/**
 * Writes a [GnssSecond] line to the continuous log for each second of [sessionId] that had GNSS
 * samples (FBBD-183). It runs on the recorder's listener thread and keeps only one second in memory.
 * [startUtcMillis] is the session's start on the phone's wall clock. [write] is the log (a test hook).
 */
class GnssSecondLog(
    private val sessionId: String,
    private val startUtcMillis: Long,
    private val write: (String) -> Unit = { DebugLog.forSession(sessionId, CheckLog.GNSS, it) },
) : RecorderListener {
    private var current: Builder? = null
    private var lastFixT: Long? = null
    private var lastUtc: Long? = null
    private var dropSinceLast = false

    /** How often each step length (to 10 ms) was seen, for the usual step. */
    private val stepCounts = HashMap<Long, Int>()

    override fun onSamples(set: SampleSet) {
        if (set.values.none { it.channelId in GnssChannels.IDS }) return
        val second = set.tMicros / MicrosPerSecond
        val open = current
        val b = if (open == null || open.second != second) {
            open?.let { write(it.build().format()) }
            Builder(second, set.tMicros).also { current = it }
        } else {
            open
        }
        add(b, set)
    }

    override fun onEvent(event: RecorderEvent) {
        if (event.kind.endsWith(DisconnectedSuffix) && event.kind != HeartRateDrop) dropSinceLast = true
    }

    override fun onStatus(status: RecorderStatus) {
        if (status is RecorderStatus.Stopped) {
            current?.let { write(it.build().format()) }
            current = null
        }
    }

    private fun add(b: Builder, set: SampleSet) {
        fun value(id: Int) = set.values.firstOrNull { it.channelId == id }?.value?.takeIf { it.isFinite() }
        b.samples++
        val drop = dropSinceLast
        dropSinceLast = false
        if (drop) b.afterDrop = true
        value(GnssChannels.UTC_MS.id)?.roundToLong()?.let { utc -> receiverTime(b, set.tMicros, utc, drop) }
        val lat = value(GnssChannels.LAT.id)
        val lon = value(GnssChannels.LON.id)
        if (lat != null && lon != null && isFix(lat, lon)) {
            b.fixes += Triple(lat, lon, value(GnssChannels.SPEED_KMH.id))
            lastFixT?.let { previous -> b.maxGap = maxOf(b.maxGap ?: 0L, set.tMicros - previous) }
            lastFixT = set.tMicros
            if (b.firstFix == null) b.firstFix = set.tMicros
        }
    }

    /** Receivers without a fix can report 0, 0: that is no position. */
    private fun isFix(lat: Double, lon: Double) = lat != 0.0 || lon != 0.0

    private fun receiverTime(b: Builder, tMicros: Long, utc: Long, drop: Boolean) {
        val previousUtc = lastUtc
        if (previousUtc != null && utc > previousUtc && !drop) step(b, utc - previousUtc)
        lastUtc = utc
        if (b.utcFirst == null) b.utcFirst = utc
        b.utcLast = utc
        val lag = tMicros - (utc - startUtcMillis) * MicrosPerMilli
        b.lag = minOf(b.lag ?: lag, lag)
    }

    private fun step(b: Builder, stepMillis: Long) {
        val key = (stepMillis + StepRoundMillis / 2) / StepRoundMillis * StepRoundMillis
        stepCounts[key] = (stepCounts[key] ?: 0) + 1
        val usual = stepCounts.maxBy { it.value }.key.coerceAtLeast(1)
        b.usual = usual
        b.missing += maxOf(0L, (stepMillis.toDouble() / usual).roundToLong() - 1).toInt()
        if (stepMillis * MicrosPerMilli > LongGapMicros) b.longGaps++
        b.maxRxGap = maxOf(b.maxRxGap ?: 0L, stepMillis)
    }

    private class Builder(val second: Long, val tMicros: Long) {
        var samples = 0
        var missing = 0
        var usual: Long? = null
        var longGaps = 0
        var maxRxGap: Long? = null
        var maxGap: Long? = null
        var firstFix: Long? = null
        var afterDrop = false
        var utcFirst: Long? = null
        var utcLast: Long? = null
        var lag: Long? = null
        val fixes = mutableListOf<Triple<Double, Double, Double?>>()

        fun build(): GnssSecond {
            val lat = fixes.map { it.first }.average().takeIf { fixes.isNotEmpty() }
            val lon = fixes.map { it.second }.average().takeIf { fixes.isNotEmpty() }
            val variance = if (lat != null && lon != null) {
                val kx = MetersPerDegree * cos(Math.toRadians(lat))
                fixes.sumOf { (a, o, _) ->
                    val dx = (o - lon) * kx
                    val dy = (a - lat) * MetersPerDegree
                    dx * dx + dy * dy
                } / fixes.size
            } else {
                null
            }
            val speeds = fixes.mapNotNull { it.third }
            return GnssSecond(
                second, tMicros, samples, fixes.size, missing, usual, longGaps, maxRxGap, maxGap, afterDrop,
                utcFirst, utcLast, lag, lat, lon, variance,
                speeds.average().takeIf { speeds.isNotEmpty() }, speeds.minOrNull(), speeds.maxOrNull(), firstFix,
            )
        }
    }

    private companion object {
        const val MicrosPerSecond = 1_000_000L
        const val MicrosPerMilli = 1_000L
        const val StepRoundMillis = 10L
        const val LongGapMicros = 200_000L
        const val MetersPerDegree = 111_195.0

        /** Link drops of the GNSS sources end so (core-ble's `*_disconnected`); heart rate is no GNSS source. */
        const val DisconnectedSuffix = "_disconnected"
        const val HeartRateDrop = "hr_disconnected"
    }
}
