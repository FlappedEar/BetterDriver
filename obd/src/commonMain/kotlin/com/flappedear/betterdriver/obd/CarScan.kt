package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.setup.CanIdRecord
import com.flappedear.betterdriver.setup.CarParameterSource
import com.flappedear.betterdriver.setup.CarProfile as SetupCar
import com.flappedear.betterdriver.setup.CarScanRecord

/**
 * One CAN id heard at the OBD port during a scan (FBBD-344): how many frames came in the scan's
 * open monitor, how many a second that is, the longest frame, which data bytes took more than one
 * value (where a signal can be) and the last frame's data in hex. With a full adapter buffer the
 * rates are low: the adapter dropped frames, not the car.
 */
data class CanIdSeen(
    val id: Int,
    val frames: Int,
    val perSecond: Double,
    val length: Int,
    val changing: Set<Int>,
    val last: String,
)

/**
 * What a car offers at its OBD port (FBBD-344), from one scan: the protocol (`ATDPN`), the mode 01
 * PIDs its ECUs report in their 01 00 … 01 C0 bitmasks, the first [VinShown] characters of its VIN
 * (maker and model, not the serial), and the CAN ids heard with their rates ([canIds]; empty with
 * [canHeard] false when the car is not on 11-bit CAN or the adapter would not monitor).
 * [bufferFull] says the adapter's buffer overflowed while monitoring.
 */
data class CarScan(
    val protocol: String,
    val supported: Set<Int>,
    val vinPrefix: String?,
    val canIds: List<CanIdSeen>,
    val canHeard: Boolean,
    val bufferFull: Boolean = false,
) {
    companion object {
        /** VIN characters kept: world maker, model and body (positions 1 to 8). */
        const val VinShown = 8

        /** The ids in [frames], heard in [ms] of monitoring, busiest first. */
        fun canIds(frames: List<Pair<Int, ByteArray>>, ms: Long): List<CanIdSeen> =
            frames.groupBy({ it.first }, { it.second }).map { (id, data) ->
                val length = data.maxOf { it.size }
                val changing = (0 until length).filterTo(LinkedHashSet()) { i ->
                    data.mapNotNullTo(HashSet()) { it.getOrNull(i) }.size > 1
                }
                CanIdSeen(
                    id = id,
                    frames = data.size,
                    perSecond = data.size * MS_PER_S / ms.coerceAtLeast(1),
                    length = length,
                    changing = changing,
                    last = data.last().joinToString("") { hex(it.toInt() and ByteMask, 2) },
                )
            }.sortedWith(compareByDescending<CanIdSeen> { it.frames }.thenBy { it.id })

        private const val MS_PER_S = 1_000.0
    }
}

/**
 * The driver's own parameters (FBBD-344): a value the app does not know, defined in the car's page as
 * an OBD-II request or a CAN broadcast id with a RaceChrono equation. Their channel ids start with
 * [Prefix] so they never replace a built-in channel, and every session with the car reads them
 * whatever the poll set ([PollSet.select]).
 */
object OwnChannels {
    const val Prefix = "custom_"

    /** The channel id for the parameter stored as [key]. */
    fun id(key: String): String = Prefix + key

    /** Whether [id] is one of the driver's own channels. */
    fun isOwn(id: String): Boolean = id.startsWith(Prefix)

    /**
     * An OBD-II request: [mode] and [pid] ([pidLength] bytes; 2 for mode 22) to [header] (null:
     * every ECU), or null when the request or the [equation] is not valid.
     */
    @Suppress("LongParameterList", "ReturnCount") // One argument per field; each early return is one invalid field.
    fun pid(
        key: String,
        name: String,
        header: Int?,
        mode: Int,
        pid: Int,
        equation: String,
        unit: String,
        fast: Boolean,
        pidLength: Int = 1,
    ): PidChannel? {
        val pids = 0 until (1 shl (Byte.SIZE_BITS * pidLength))
        if (mode !in 1..MAX_REQUEST_MODE || pidLength !in 1..2 || pid !in pids) return null
        if (header != null && header !in 0..MAX_29_BIT) return null
        val channel = PidChannel(
            id(key), name, header, mode, pid, equation, unit, if (fast) PollClass.FAST else PollClass.SLOW, pidLength,
        )
        return channel.takeIf { it.compiled != null }
    }

    /**
     * A broadcast on 11-bit CAN id [canId], read whenever it comes: [equation] over its data bytes,
     * or null when the id or the equation is not valid.
     */
    fun can(key: String, name: String, canId: Int, equation: String, unit: String): CanSignal? {
        if (canId !in 0..MAX_11_BIT) return null
        val signal = CanSignal(id(key), name, canId, equation, unit, periodMs = OWN_PERIOD_MS)
        return signal.takeIf { it.channel.compiled != null }
    }

    /** The broadcast mode is never a request. */
    private const val MAX_REQUEST_MODE = CanSignal.BroadcastMode - 1
    private const val MAX_11_BIT = 0x7FF
    private const val MAX_29_BIT = 0x1FFFFFFF

    /** How far apart the frames of one listen are stamped; the car's own period is not known. */
    private const val OWN_PERIOD_MS = 20L
}

/**
 * The OBD profile of [car] (FBBD-344): the generic one with the car's own parameters, each that is
 * valid as a request ([OwnChannels.pid]) or a broadcast ([OwnChannels.can]). Invalid ones are left out.
 */
fun ownProfile(car: SetupCar?): CarProfile {
    val own = car?.parameters.orEmpty()
    val pids = own.filter { it.source == CarParameterSource.OBD }.mapNotNull {
        OwnChannels.pid(it.key, it.name, it.header, it.mode, it.pid, it.equation, it.unit, it.fast, it.pidLength)
    }
    val signals = own.filter { it.source == CarParameterSource.CAN }.mapNotNull {
        OwnChannels.can(it.key, it.name, it.canId, it.equation, it.unit)
    }
    return CarProfile.Generic.copy(channels = pids, signals = signals)
}

/** [scan] as it is stored with the car, taken at [atMs] wall-clock time. */
fun CarScan.toRecord(atMs: Long): CarScanRecord = CarScanRecord(
    atMs = atMs,
    protocol = protocol,
    supported = supported.sorted(),
    vinPrefix = vinPrefix,
    canHeard = canHeard,
    canIds = canIds.map { CanIdRecord(it.id, it.perSecond, it.length, it.changing.sorted(), it.last) },
    bufferFull = bufferFull,
)
