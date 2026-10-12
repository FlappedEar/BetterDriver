package com.flappedear.betterdriver.recorder.live

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
    private var storageLowBytes: Long? = null
    private var stoppedReason: String? = null

    override fun onSamples(set: SampleSet) = synchronized(this) {
        var lat = false
        var lon = false
        for (v in set.values) {
            when (v.channelId) {
                GnssChannels.LAT.id -> lat = true
                GnssChannels.LON.id -> lon = true
                GnssChannels.SATS.id -> sats = v.value.toInt()
                GnssChannels.SPEED_KMH.id -> speedKmh = v.value
                accelIds?.first -> accelX = v.value
                accelIds?.second -> accelY = v.value
            }
        }
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
            gCombined = accelX?.let { x -> accelY?.let { y -> hypot(x, y) } },
            receiverRateHz = receiverRateHz,
        )
    }

    /** Fixes a second from the mean interval of those in the [RateWindowMicros] up to [fix]. */
    private fun measureRate(fix: Long): Int {
        rateFixes.addLast(fix)
        while (rateFixes.first() < fix - RateWindowMicros) rateFixes.removeFirst()
        val span = rateFixes.last() - rateFixes.first()
        return if (span <= 0) 0 else ((rateFixes.size - 1) * MicrosPerSecond.toDouble() / span).roundToInt()
    }

    /** Keeps only fixes from the second up to [now]. */
    private fun trim(now: Long) {
        while (recentFixes.isNotEmpty() && recentFixes.first() <= now - MicrosPerSecond) recentFixes.removeFirst()
    }
}
