package com.flappedear.betterdriver.recorder.log

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.PI
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
 * came, which ends any wait over a second. [lagMicros] is the smallest, over the samples with a fix,
 * of (session time − receiver time since the session's start): how far the phone's clock is from the
 * receiver's, plus the shortest delivery delay. [lat], [lon] are the mean fix, [varianceM2] the mean
 * squared distance of the fixes from it; [kmh] the mean speed, [kmhMin] and [kmhMax] its range.
 * Since 1.3.0 the steps that start before the session's first fix count in [preFixLongGaps] and
 * [maxRxPreGapMillis] instead of [longGaps] and [maxRxGapMillis] (FBBD-273); older lines count them in both.
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
    /** Of [missing], single samples lost on a whole second between two fixes: a RaceBox's skip (FBBD-170). */
    val missingOnSecond: Int = 0,
    /** Steps over 200 ms up to the session's first fix (FBBD-273). */
    val preFixLongGaps: Int = 0,
    /** The longest step on the receiver's clock up to the session's first fix (FBBD-273). */
    val maxRxPreGapMillis: Long? = null,
) {
    /** The line's message: `key=value` pairs; a value not known is left out. */
    fun format(): String = listOf(
        "sec" to second, "t_us" to tMicros, "n" to samples, "fix" to fixes, "miss" to missing,
        "step_ms" to stepMillis, "long" to longGaps, "max_rx_ms" to maxRxGapMillis, "max_us" to maxGapMicros,
        "drop" to (1.takeIf { afterDrop }), "utc0" to utcFirst, "utc1" to utcLast, "lag_us" to lagMicros,
        "lat" to decimal(lat, Degrees), "lon" to decimal(lon, Degrees), "var_m2" to decimal(varianceM2, Variance),
        "kmh" to decimal(kmh, Speed), "kmh_min" to decimal(kmhMin, Speed), "kmh_max" to decimal(kmhMax, Speed),
        "fix_us" to firstFixMicros, "miss_s" to missingOnSecond.takeIf { it > 0 },
        "long_pre" to preFixLongGaps.takeIf { it > 0 }, "max_rx_pre_ms" to maxRxPreGapMillis,
    ).filter { it.second != null }.joinToString(" ") { (k, v) -> "$k=$v" }

    private fun decimal(value: Double?, digits: Int) = value?.let { fixedDecimals(it, digits) }

    companion object {
        private const val Degrees = 7
        private const val Variance = 4
        private const val Speed = 2

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
                missingOnSecond = f["miss_s"]?.toIntOrNull() ?: 0,
                preFixLongGaps = f["long_pre"]?.toIntOrNull() ?: 0,
                maxRxPreGapMillis = f["max_rx_pre_ms"]?.toLongOrNull(),
            )
        }
    }
}

/**
 * Writes a [GnssSecond] line to the continuous log for each second of [sessionId] that had GNSS
 * samples (FBBD-183). It runs on the recorder's listener thread and keeps only one second in memory.
 * [startUtcMillis] is the session's start on the phone's wall clock. [write] is the log (a test hook).
 * [skipsWithoutFix]: the receiver is a RaceBox Micro, which skips its whole-second message without a
 * fix too (2026-10-16 midday); a Mini sends it then, so for a Mini only a loss between fixes is the skip.
 */
class GnssSecondLog(
    private val sessionId: String,
    private val startUtcMillis: Long,
    private val skipsWithoutFix: Boolean = false,
    private val write: (String) -> Unit = { CoreLog.forSession(sessionId, CheckLog.GNSS, it) },
) : RecorderListener {
    private var current: Builder? = null
    private var lastFixT: Long? = null
    private var lastUtc: Long? = null
    private var lastUtcFix = false
    private var dropSinceLast = false

    /** The session has had a fix (FBBD-273). */
    private var fixSeen = false

    /** The receiver time [lastUtc] came before any fix: a step from it is not judged (FBBD-273). */
    private var lastUtcPreFix = true

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
        val lat = value(GnssChannels.LAT.id)
        val lon = value(GnssChannels.LON.id)
        val fix = lat != null && lon != null && isFix(lat, lon)
        value(GnssChannels.UTC_MS.id)?.roundToLong()?.let { utc -> receiverTime(b, set.tMicros, utc, drop, fix) }
        // After the step it ends, and with or without the receiver's time (Codex on #260).
        if (fix) fixSeen = true
        if (fix && lat != null && lon != null) {
            b.fixes += Triple(lat, lon, value(GnssChannels.SPEED_KMH.id))
            lastFixT?.let { previous -> b.maxGap = maxOf(b.maxGap ?: 0L, set.tMicros - previous) }
            lastFixT = set.tMicros
            if (b.firstFix == null) b.firstFix = set.tMicros
        }
    }

    /** Receivers without a fix can report 0, 0: that is no position. */
    private fun isFix(lat: Double, lon: Double) = lat != 0.0 || lon != 0.0

    private fun receiverTime(b: Builder, tMicros: Long, utc: Long, drop: Boolean, fix: Boolean) {
        val previousUtc = lastUtc
        if (previousUtc != null && utc > previousUtc && !drop) {
            step(b, previousUtc, utc - previousUtc, fix && lastUtcFix, preFix = lastUtcPreFix)
        }
        lastUtcPreFix = !fixSeen && !fix
        lastUtc = utc
        lastUtcFix = fix
        if (b.utcFirst == null) b.utcFirst = utc
        b.utcLast = utc
        // Before its first fix a receiver's time is its own guess, a few hundred ms off (2026-10-18 log).
        if (!fix) return
        val lag = tMicros - (utc - startUtcMillis) * MicrosPerMilli
        b.lag = minOf(b.lag ?: lag, lag)
    }

    private fun step(b: Builder, fromUtc: Long, stepMillis: Long, fixBothSides: Boolean, preFix: Boolean) {
        val key = (stepMillis + StepRoundMillis / 2) / StepRoundMillis * StepRoundMillis
        stepCounts[key] = (stepCounts[key] ?: 0) + 1
        val usual = stepCounts.maxBy { it.value }.key.coerceAtLeast(1)
        b.usual = usual
        val lost = maxOf(0L, (stepMillis.toDouble() / usual).roundToLong() - 1).toInt()
        b.missing += lost
        val skip = lost == 1 && (fixBothSides || skipsWithoutFix)
        if (skip && onWholeSecond(fromUtc + stepMillis / 2, usual)) b.missingOnSecond++
        val long = stepMillis * MicrosPerMilli > LongGapMicros
        if (preFix) {
            if (long) b.preFixLongGaps++
            b.maxRxPreGap = maxOf(b.maxRxPreGap ?: 0L, stepMillis)
        } else {
            if (long) b.longGaps++
            b.maxRxGap = maxOf(b.maxRxGap ?: 0L, stepMillis)
        }
    }

    private fun onWholeSecond(utc: Long, step: Long): Boolean {
        val ms = utc.mod(MillisPerSecond)
        return ms < step / 2 || ms > MillisPerSecond - step / 2
    }

    private class Builder(val second: Long, val tMicros: Long) {
        var samples = 0
        var missing = 0
        var missingOnSecond = 0
        var usual: Long? = null
        var longGaps = 0
        var maxRxGap: Long? = null
        var preFixLongGaps = 0
        var maxRxPreGap: Long? = null
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
                val kx = MetersPerDegree * cos(lat * RadiansPerDegree)
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
                missingOnSecond, preFixLongGaps, maxRxPreGap,
            )
        }
    }

    private companion object {
        const val MicrosPerSecond = 1_000_000L
        const val MicrosPerMilli = 1_000L
        const val MillisPerSecond = 1_000L
        const val StepRoundMillis = 10L
        const val LongGapMicros = 200_000L
        const val MetersPerDegree = 111_195.0

        // As the JVM's Math.toRadians multiplies, so both apps give the same metres.
        const val RadiansPerDegree = PI / 180.0

        /** Link drops of the GNSS sources end so (core-ble's `*_disconnected`); heart rate is no GNSS source. */
        const val DisconnectedSuffix = "_disconnected"
        const val HeartRateDrop = HeartRateLogIds.DISCONNECTED
    }
}
