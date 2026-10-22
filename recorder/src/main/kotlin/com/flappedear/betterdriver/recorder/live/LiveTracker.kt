package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MicrosPerSecond = 1_000_000L

/** Fixes this far back set the receiver's rate: long enough that one late fix at 1 Hz is averaged out. */
private const val RateWindowMicros = 3 * MicrosPerSecond

/** The shortest wait for a fix before the GNSS signal counts as lost, whatever the receiver's rate. */
const val GnssLostMinMicros = MicrosPerSecond

/**
 * A car or body value is shown at least this long after it arrived, and at least [StalePeriods] of
 * its own sampling periods: a slow OBD-II PID arrives every 5 s (FBBD-262).
 */
private const val StaleMinMicros = 3 * MicrosPerSecond
private const val StalePeriods = 3

/**
 * A car or body value is shown as the mean of its readings this far back from the newest one
 * (FBBD-303): the screen refreshes every 250 ms, and RPM at 15-20 Hz showed one reading in four and
 * jumped. A channel read less often than this shows its latest reading, as before.
 */
private const val MeanWindowMicros = 300_000L

/**
 * A channel's sampling period is the shortest of its last gaps: one outage is a single long gap,
 * while a PID polled every 13 s (many slow PIDs on a slow adapter) has every gap that long.
 */
private const val PeriodGaps = 4

/** OBD-II channels are named "obd_<id>" with source "obd" (core-ble ObdSource). */
private const val ObdSource = "obd"

/** The heart-rate monitor's beats per minute (core-ble HeartRateChannels.HEART_RATE). */
private const val HeartRateName = "heart_rate"

/** The channels whose latest value the track screen can show besides GNSS: every OBD-II value and heart rate. */
fun isShownLive(def: ChannelDef): Boolean = def.source == ObdSource || def.name == HeartRateName

/** Fix periods to wait: one late NMEA sentence at 1 Hz must not show the banner (FBBD-64). */
private const val GnssLostPeriods = 3

/**
 * How long after the last fix the GNSS signal counts as lost: three fix periods of a receiver
 * sending [fixRateHz] fixes a second, and at least [GnssLostMinMicros]. So 3 s at 1 Hz and 1 s at
 * 10 or 25 Hz. A rate of 0 (not measured yet) counts as 1 Hz.
 */
fun gnssLostAfterMicros(fixRateHz: Int): Long =
    maxOf(GnssLostMinMicros, GnssLostPeriods * MicrosPerSecond / fixRateHz.coerceAtLeast(1))

enum class GnssState {
    /** No fix yet since the session started. */
    Waiting,
    Ok,
    /** Fixes stopped arriving ([gnssLostAfterMicros]). */
    Lost,
}

/**
 * What the track screen needs from a running recording, small enough to send to the UI process
 * several times a second. Session times are microseconds on the recorder's clock.
 */
data class LiveSnapshot(
    val sessionId: String,
    val startUtcMillis: Long,
    val elapsedMicros: Long,
    val fixes: Long,
    /** Fixes in the last second. */
    val fixRateHz: Int,
    /** Null before the first fix. */
    val lastFixAgeMicros: Long?,
    val sats: Int?,
    val speedKmh: Double?,
    /** Free bytes when the recorder last warned about storage, else null. */
    val storageLowBytes: Long?,
    /** Why the recording stopped, or null while it runs. */
    val stoppedReason: String?,
    val droppedSamples: Long,
    /** Combined horizontal acceleration in g from the receiver's accelerometer; null without one. */
    val gCombined: Double? = null,
    /**
     * The receiver's rate, from the mean interval between fixes in the 3 s up to the last one; 0
     * before the second fix. Unlike [fixRateHz] it does not fall while fixes are missing, so it sets
     * how long to wait before [GnssState.Lost].
     */
    val receiverRateHz: Int = 0,
    /**
     * The latest value of each OBD-II and heart-rate channel, by channel name ("obd_coolant_temp",
     * "heart_rate"), for the track screen's tiles (FBBD-262). A channel with no value yet is left out,
     * so its tile is left out (UI-126 B); a value that stopped arriving is NaN, so its tile shows a
     * dash and never an old reading as current.
     */
    val channels: Map<String, Double> = emptyMap(),
) {
    val gnss: GnssState
        get() = when {
            lastFixAgeMicros == null -> GnssState.Waiting
            lastFixAgeMicros > gnssLostAfterMicros(receiverRateHz) -> GnssState.Lost
            else -> GnssState.Ok
        }
}

/** A change of GNSS state worth a short sound on the track screen (FBBD-64). */
enum class GnssCue {
    /** Fixes stopped after the signal was OK. */
    Lost,

    /** Fixes came back after [Lost]. */
    Back,
}

/** The cue for a change from [before] to [now], or null. The first fix of a session is not a cue. */
fun gnssCue(before: GnssState?, now: GnssState?): GnssCue? = when {
    before == GnssState.Ok && now == GnssState.Lost -> GnssCue.Lost
    before == GnssState.Lost && now == GnssState.Ok -> GnssCue.Back
    else -> null
}

/**
 * Keeps the latest live values of one recording. It is a [RecorderListener], so it runs on the
 * recorder's listener thread and can never hold up or break the recording (FBBD-69). [snapshot]
 * reads the clock, so a snapshot taken on a timer shows [GnssState.Lost] without a new sample.
 */
class LiveTracker(
    private val sessionId: String,
    private val startUtcMillis: Long,
    /** The recording's clock ([com.flappedear.betterdriver.recorder.Recording.nowMicros]). */
    private val nowMicros: () -> Long,
    private val droppedSamples: () -> Long = { 0L },
    /** Channel ids of the receiver's X and Y acceleration in g, when it has an accelerometer. */
    private val accelIds: Pair<Int, Int>? = null,
) : RecorderListener {

    private val recentFixes = ArrayDeque<Long>()
    private val rateFixes = ArrayDeque<Long>()
    private var fixes = 0L
    private var lastFixMicros: Long? = null
    private var receiverRateHz = 0
    private var sats: Int? = null
    private var speedKmh: Double? = null
    private var accelX: Double? = null
    private var accelY: Double? = null
    private val standing = StandingBias()
    private var storageLowBytes: Long? = null
    private var stoppedReason: String? = null
    private val shown = HashMap<Int, ShownValue>()

    override fun onChannel(def: ChannelDef) = synchronized(this) {
        if (isShownLive(def)) shown[def.id] = ShownValue(def.name)
    }

    override fun onSamples(set: SampleSet) = synchronized(this) {
        var lat = false
        var lon = false
        var accel = false
        for (v in set.values) {
            when (v.channelId) {
                GnssChannels.LAT.id -> lat = true
                GnssChannels.LON.id -> lon = true
                GnssChannels.SATS.id -> sats = v.value.toInt()
                GnssChannels.SPEED_KMH.id -> speedKmh = v.value
                accelIds?.first -> {
                    accelX = v.value
                    accel = true
                }
                accelIds?.second -> accelY = v.value
                else -> shown[v.channelId]?.update(set.tMicros, v.value)
            }
        }
        if (accel) standing.update(speedKmh, accelX, accelY)
        if (lat && lon) {
            fixes++
            lastFixMicros = maxOf(lastFixMicros ?: Long.MIN_VALUE, set.tMicros)
            recentFixes.addLast(set.tMicros)
            trim(set.tMicros)
            receiverRateHz = measureRate(set.tMicros)
        }
    }

    override fun onStatus(status: RecorderStatus) = synchronized(this) {
        when (status) {
            is RecorderStatus.StorageLow -> storageLowBytes = status.usableBytes
            is RecorderStatus.Stopped -> stoppedReason = status.reason
        }
    }

    fun snapshot(): LiveSnapshot = synchronized(this) {
        val now = nowMicros()
        trim(now)
        LiveSnapshot(
            sessionId = sessionId,
            startUtcMillis = startUtcMillis,
            elapsedMicros = now,
            fixes = fixes,
            fixRateHz = recentFixes.size,
            lastFixAgeMicros = lastFixMicros?.let { (now - it).coerceAtLeast(0) },
            sats = sats,
            speedKmh = speedKmh,
            storageLowBytes = storageLowBytes,
            stoppedReason = stoppedReason,
            droppedSamples = droppedSamples(),
            gCombined = accelX?.let { x -> accelY?.let { y -> hypot(x - standing.x, y - standing.y) } },
            receiverRateHz = receiverRateHz,
            channels = shown.values.mapNotNull { it.current(now) }.toMap(),
        )
    }

    /**
     * Fixes a second from the mean interval of those in the [RateWindowMicros] up to [fix]. A single
     * fix in the window (the first after a gap) keeps the rate measured before it.
     */
    private fun measureRate(fix: Long): Int {
        rateFixes.addLast(fix)
        while (rateFixes.first() < fix - RateWindowMicros) rateFixes.removeFirst()
        val span = rateFixes.last() - rateFixes.first()
        if (span <= 0) return receiverRateHz
        return ((rateFixes.size - 1) * MicrosPerSecond.toDouble() / span).roundToInt()
    }

    /** Keeps only fixes from the second up to [now]. */
    private fun trim(now: Long) {
        while (recentFixes.isNotEmpty() && recentFixes.first() <= now - MicrosPerSecond) recentFixes.removeFirst()
    }
}

/** The recent values of one shown channel and how often it arrives. */
private class ShownValue(val name: String) {
    /** Readings in the [MeanWindowMicros] up to the newest, as time and value. */
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private var atMicros = Long.MIN_VALUE
    private val gaps = LongArray(PeriodGaps)
    private var gapCount = 0
    private var periodMicros = 0L

    fun update(t: Long, v: Double) {
        if (!v.isFinite()) return
        if (atMicros != Long.MIN_VALUE && t > atMicros) {
            gaps[gapCount++ % PeriodGaps] = t - atMicros
            periodMicros = (0 until minOf(gapCount, PeriodGaps)).minOf { gaps[it] }
        }
        atMicros = maxOf(atMicros, t)
        recent.addLast(t to v)
        recent.removeAll { it.first < atMicros - MeanWindowMicros }
    }

    /**
     * Name and the mean of the readings in the [MeanWindowMicros] up to the newest; null before the
     * first value, and NaN once the newest is older than its staleness limit.
     */
    fun current(now: Long): Pair<String, Double>? {
        if (atMicros == Long.MIN_VALUE) return null
        val limit = maxOf(StaleMinMicros, StalePeriods * periodMicros)
        return name to if (now - atMicros > limit) Double.NaN else recent.sumOf { it.second } / recent.size
    }
}

/**
 * What the accelerometer reads while the car stands still: gravity along a tilted mount (a RaceBox
 * pitched back by 11° reads 0.19 g at a standstill), taken off the live G so a standing car reads
 * 0.00 g. Averaged over about a second of readings below [StandingKmh]; zero until the car has stood.
 */
internal class StandingBias {
    var x = 0.0
        private set
    var y = 0.0
        private set
    private var seen = false

    fun update(speedKmh: Double?, ax: Double?, ay: Double?) {
        val standing = speedKmh != null && speedKmh < StandingKmh
        if (!standing || ax == null || ay == null) return
        if (!ax.isFinite() || !ay.isFinite()) return
        if (seen) {
            x += (ax - x) * StandingWeight
            y += (ay - y) * StandingWeight
        } else {
            x = ax
            y = ay
            seen = true
        }
    }

    private companion object {
        /** Below this the car stands (GNSS speed noise at a standstill stays under it). */
        const val StandingKmh = 1.0

        /** Each standing reading's share: about a second at the RaceBox's 25 Hz. */
        const val StandingWeight = 0.04
    }
}
