package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.core.JvmSynchronized
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
    /**
     * Combined horizontal acceleration in g from the receiver's accelerometer, its X and Y each
     * averaged over the last [GSmoothingMicros] (FBBD-302); null without one.
     */
    val gCombined: Double? = null,
    /**
     * The G radar's dot (FBBD-318): the same half-second average as [gCombined], turned to the car's
     * forward and left; null before the first reading.
     */
    val g: GPoint? = null,
    /** The radar's trail behind [g], oldest first: a point every 0.1 s of the half second before it. */
    val gTrail: List<GPoint> = emptyList(),
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
    /** The last fix's position, for the map screen's car (UI-171 B); null before the first fix. */
    val latitude: Double? = null,
    val longitude: Double? = null,
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
 * Keeps the latest live values of one recording. It is a [RecorderListener], so on Android it runs on
 * the recorder's listener thread and can never hold up or break the recording (FBBD-69). [snapshot]
 * reads the clock, so a snapshot taken on a timer shows [GnssState.Lost] without a new sample.
 * Synchronised on the JVM; on iOS the caller serialises the calls.
 */
class LiveTracker(
    private val sessionId: String,
    private val startUtcMillis: Long,
    /** The recording's clock ([com.flappedear.betterdriver.recorder.Recording.nowMicros]). */
    private val nowMicros: () -> Long,
    private val droppedSamples: () -> Long = { 0L },
    /** Channel ids of the receiver's X and Y acceleration in g, when it has an accelerometer. */
    private val accelIds: Pair<Int, Int>? = null,
    /**
     * The Z acceleration's channel id when [accelIds] are the phone's own accelerometer, which can sit
     * any way up in its mount (receivers without one: phone GPS).
     */
    private val accelZId: Int? = null,
) : RecorderListener {

    private val recentFixes = ArrayDeque<Long>()
    private val rateFixes = ArrayDeque<Long>()
    private var fixes = 0L
    private var lastFixMicros: Long? = null
    private var receiverRateHz = 0
    private var sats: Int? = null
    private var speedKmh: Double? = null
    private var latitude: Double? = null
    private var longitude: Double? = null
    private var accelX: Double? = null
    private var accelY: Double? = null
    private var accelZ: Double? = null
    private val axes = if (accelZId == null) ReceiverAxes else PhoneAxes
    private var gWindow = GWindow(axes)
    private var gg = LiveGg(axes)
    private val level = Level()
    private val standing = StandingBias()
    private var storageLowBytes: Long? = null
    private var stoppedReason: String? = null
    private val shown = HashMap<Int, ShownValue>()

    @JvmSynchronized
    override fun onChannel(def: ChannelDef) {
        if (isShownLive(def)) shown[def.id] = ShownValue(def.name)
    }

    @JvmSynchronized
    override fun onSamples(set: SampleSet) {
        var lat: Double? = null
        var lon: Double? = null
        var accel = false
        var speed: Double? = null
        var heading: Double? = null
        for (v in set.values) {
            when (v.channelId) {
                GnssChannels.LAT.id -> lat = v.value
                GnssChannels.LON.id -> lon = v.value
                GnssChannels.SATS.id -> sats = v.value.toInt()
                GnssChannels.SPEED_KMH.id -> {
                    speedKmh = v.value
                    speed = v.value
                }
                GnssChannels.HEADING.id -> heading = v.value
                accelIds?.first -> {
                    accelX = v.value
                    accel = true
                }
                accelIds?.second -> accelY = v.value
                accelZId -> accelZ = v.value
                else -> shown[v.channelId]?.update(set.tMicros, v.value)
            }
        }
        motion(set.tMicros, accel, speed, heading)
        if (lat != null && lon != null) {
            place(set.tMicros, lat, lon)
            fixes++
            lastFixMicros = maxOf(lastFixMicros ?: Long.MIN_VALUE, set.tMicros)
            recentFixes.addLast(set.tMicros)
            trim(set.tMicros)
            receiverRateHz = measureRate(set.tMicros)
        }
    }

    /**
     * The G tile's and the radar's readings from one set: [accel] when it carried the receiver's
     * acceleration, [speed] and [heading] when it carried a fix.
     */
    private fun motion(t: Long, accel: Boolean, speed: Double?, heading: Double?) {
        speed?.let { gg.addFix(t, it, heading) }
        if (accel) {
            standing.update(speedKmh, accelX, accelY)
            var a = reading()
            if (a != null && axes == PhoneAxes) {
                // Levelled the first time the car stands: the radar starts its match again on the new axes.
                if (level.update(speedKmh, a)) {
                    gWindow = GWindow(axes)
                    gg = LiveGg(axes, turned = true, fixesFrom = gg)
                }
                a = level.apply(a)
            }
            a?.let { gWindow.add(t, it) }
            // The radar's match takes each axis's tilt and gravity off itself, as the session goes.
            gWindow.means()?.let { gg.addAccel(t, it) }
        } else if (speed != null) {
            gg.fixOnly(t)
        }
    }

    /** The latest value of each accelerometer axis, or null before all have one. */
    private fun reading(): DoubleArray? {
        val values = if (axes == PhoneAxes) listOf(accelX, accelY, accelZ) else listOf(accelX, accelY)
        return values.filterNotNull().takeIf { it.size == axes }?.toDoubleArray()
    }

    /** The car's position from the newest fix only: a late one is older. */
    private fun place(t: Long, lat: Double, lon: Double) {
        if (t < (lastFixMicros ?: Long.MIN_VALUE)) return
        latitude = lat
        longitude = lon
    }

    @JvmSynchronized
    override fun onStatus(status: RecorderStatus) {
        when (status) {
            is RecorderStatus.StorageLow -> storageLowBytes = status.usableBytes
            is RecorderStatus.Stopped -> stoppedReason = status.reason
        }
    }

    @JvmSynchronized
    fun snapshot(): LiveSnapshot {
        val now = nowMicros()
        trim(now)
        return LiveSnapshot(
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
            // The phone's axes are the car's only once matched to GPS: its G is the radar's.
            gCombined = if (axes == 2) gWindow.combined(standing) else gg.current()?.let { hypot(it.forward, it.left) },
            g = gg.current(),
            gTrail = gg.trail(),
            receiverRateHz = receiverRateHz,
            channels = shown.values.mapNotNull { it.current(now) }.toMap(),
            latitude = latitude,
            longitude = longitude,
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
 * The accelerometer's readings of the last [GSmoothingMicros] before the newest, per axis. Each axis
 * is averaged before they are combined, so road vibration cancels out instead of adding to the G.
 */
internal class GWindow(private val axes: Int = 2) {
    private val times = ArrayDeque<Long>()
    private val readings = ArrayDeque<DoubleArray>()
    private val sums = DoubleArray(axes)
    private var newest = Long.MIN_VALUE

    fun add(t: Long, a: DoubleArray) {
        if (a.size != axes || a.any { !it.isFinite() }) return
        times.addLast(t)
        readings.addLast(a.copyOf())
        for (i in 0 until axes) sums[i] += a[i]
        newest = maxOf(newest, t)
        while (times.isNotEmpty() && times.first() <= newest - GSmoothingMicros) {
            times.removeFirst()
            val old = readings.removeFirst()
            for (i in 0 until axes) sums[i] -= old[i]
        }
    }

    /** Each axis averaged; null before the first reading. */
    fun means(): DoubleArray? = if (times.isEmpty()) null else DoubleArray(axes) { sums[it] / times.size }

    /** The averaged X and Y combined, [standing] taken off; null before the first reading. */
    fun combined(standing: StandingBias): Double? =
        means()?.let { hypot(it[0] - standing.x, it[1] - standing.y) }
}

/**
 * Turns the phone's three axes so one points up, as gravity reads with the car standing, and the
 * other two lie flat: a phone upright in its mount and tilted back reads the car's forward G on its
 * Z axis only in part (cos 28° on Arek's Jastrząb mount), and braking pitches gravity into it. Until
 * the car has stood for [StandingReadings] the axes are the phone's own.
 */
internal class Level {
    private val sum = DoubleArray(PhoneAxes)
    private var count = 0
    private var basis: Array<DoubleArray>? = null

    /** Adds a standing reading; true when this one levels the axes. */
    fun update(speedKmh: Double?, a: DoubleArray): Boolean {
        if (basis != null || speedKmh == null || speedKmh >= StandingKmh) return false
        for (i in 0 until PhoneAxes) sum[i] += a[i]
        if (++count >= StandingReadings) basis = basisFrom(sum)
        return basis != null
    }

    /** [a] on the levelled axes (across, along, up), or as it is before the car has stood. */
    fun apply(a: DoubleArray): DoubleArray = basis?.let { b -> DoubleArray(PhoneAxes) { dot(b[it], a) } } ?: a

    /**
     * Up from gravity [g], the flat axis nearest the phone's X, and the one across both: X of a phone
     * upright or flat in its mount lies across the car. A phone on its side has X up: then its Y.
     */
    private fun basisFrom(g: DoubleArray): Array<DoubleArray>? {
        val up = unit(g)
        val axis = if (up == null || abs(up[0]) < MaxUpright) 0 else 1
        val across = up?.let { u -> unit(DoubleArray(PhoneAxes) { (if (it == axis) 1.0 else 0.0) - u[axis] * u[it] }) }
        if (up == null || across == null) return null
        val along = doubleArrayOf(
            up[1] * across[2] - up[2] * across[1],
            up[2] * across[0] - up[0] * across[2],
            up[0] * across[1] - up[1] * across[0],
        )
        return arrayOf(across, along, up)
    }

    private fun dot(u: DoubleArray, v: DoubleArray): Double = u.indices.sumOf { u[it] * v[it] }

    private fun unit(v: DoubleArray): DoubleArray? {
        val n = sqrt(dot(v, v))
        return if (n < MinNorm) null else DoubleArray(PhoneAxes) { v[it] / n }
    }

    private companion object {
        const val StandingKmh = 1.0

        /** A second of standing at the phone's 25 Hz. */
        const val StandingReadings = 25

        /** Gravity reads about 1 g; a phone X along gravity leaves no flat X to take. */
        const val MinNorm = 0.1

        /** A phone X this near up (about 60° from flat) is not taken as the flat axis. */
        const val MaxUpright = 0.87
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
