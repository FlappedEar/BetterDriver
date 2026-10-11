package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.RecorderListener
import com.flappedear.betterdriver.recorder.RecorderStatus
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.math.hypot

private const val MicrosPerSecond = 1_000_000L

/** No fix for this long after the last one means the GNSS signal is lost. */
const val GnssLostAfterMicros = 2 * MicrosPerSecond

enum class GnssState {
    /** No fix yet since the session started. */
    Waiting,
    Ok,
    /** Fixes stopped arriving ([GnssLostAfterMicros]). */
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
) {
    val gnss: GnssState
        get() = when {
            lastFixAgeMicros == null -> GnssState.Waiting
            lastFixAgeMicros > GnssLostAfterMicros -> GnssState.Lost
            else -> GnssState.Ok
        }
}

/**
 * Keeps the latest live values of one recording. It is a [RecorderListener], so it runs on the
 * recorder's listener thread and can never hold up or break the recording (FBBD-69).
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
    private var fixes = 0L
    private var lastFixMicros: Long? = null
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
        )
    }

    /** Keeps only fixes from the second up to [now]. */
    private fun trim(now: Long) {
        while (recentFixes.isNotEmpty() && recentFixes.first() <= now - MicrosPerSecond) recentFixes.removeFirst()
    }
}
