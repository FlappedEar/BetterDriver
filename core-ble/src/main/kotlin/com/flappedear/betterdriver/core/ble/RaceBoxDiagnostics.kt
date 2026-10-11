@file:Suppress("MagicNumber") // Byte offsets of the documented RaceBox reply payloads.

package com.flappedear.betterdriver.core.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** RaceBox reply and command ids (RaceBox BLE Protocol Description rev 8). */
object RaceBoxMessages {
    const val ACK = 0x02
    const val NACK = 0x03
    const val RECORDING_STATUS = 0x22
    const val GNSS_CONFIG = 0x27

    /**
     * Read-only queries the diagnostics screen sends. An empty payload asks the device for its
     * current value and changes nothing (p. 9 and p. 11).
     */
    fun gnssConfigQuery(): ByteArray = ubxPacket(RaceBoxClass, GNSS_CONFIG, ByteArray(0))
    fun recordingStatusQuery(): ByteArray = ubxPacket(RaceBoxClass, RECORDING_STATUS, ByteArray(0))
}

/** Reply to the GNSS receiver configuration query (0xFF 0x27, firmware 3.3 and later, p. 9–10). */
data class RaceBoxGnssConfig(val platformModel: Int, val speed3d: Boolean, val minHorizontalAccuracyM: Int) {
    /** RaceBox's names for the values it documents; others are shown as a number. */
    val platformName: String
        get() = when (platformModel) {
            4 -> "Automotive"
            5 -> "Sea"
            6 -> "Airborne, low dynamics"
            8 -> "Airborne, high dynamics"
            else -> "Model $platformModel"
        }

    companion object {
        fun parse(payload: ByteArray): RaceBoxGnssConfig? {
            if (payload.size != 3) return null
            return RaceBoxGnssConfig(payload[0].toInt() and 0xFF, payload[1].toInt() != 0, payload[2].toInt() and 0xFF)
        }
    }
}

/** Reply to the standalone recording status query (0xFF 0x22, Mini S and Micro, p. 11). */
data class RaceBoxMemoryStatus(
    val recording: Boolean,
    val levelPercent: Int,
    val securityEnabled: Boolean,
    val unlocked: Boolean,
    val storedMessages: Long,
    val capacityMessages: Long,
) {
    companion object {
        fun parse(payload: ByteArray): RaceBoxMemoryStatus? {
            if (payload.size != 12) return null
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val security = payload[2].toInt()
            return RaceBoxMemoryStatus(
                recording = payload[0].toInt() != 0,
                levelPercent = payload[1].toInt() and 0xFF,
                securityEnabled = security and 0x01 != 0,
                unlocked = security and 0x02 != 0,
                storedMessages = b.getInt(4).toLong() and 0xFFFF_FFFFL,
                capacityMessages = b.getInt(8).toLong() and 0xFFFF_FFFFL,
            )
        }
    }
}

/** Power as the device reports it in byte 67 of the data message (p. 6). */
sealed interface RaceBoxPower {
    /** RaceBox Micro: input voltage. */
    data class Input(val volts: Double) : RaceBoxPower

    /** RaceBox Mini / Mini S: battery estimate and whether it is charging. */
    data class Battery(val percent: Int, val charging: Boolean) : RaceBoxPower

    companion object {
        fun of(raw: Int, isMicro: Boolean): RaceBoxPower =
            if (isMicro) Input(raw / 10.0) else Battery(raw and 0x7F, raw and 0x80 != 0)
    }
}

/** Device Information Service strings (p. 1). Null where the device did not answer. */
data class RaceBoxDeviceInfo(
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val manufacturer: String? = null,
) {
    /** Firmware 3.3 and later answers the GNSS configuration query (p. 9). */
    val supportsGnssConfig: Boolean
        get() {
            val parts = firmware?.trim()?.split('.') ?: return false
            val major = parts.getOrNull(0)?.toIntOrNull() ?: return false
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return major > 3 || major == 3 && minor >= 3
        }
}

/** Everything the diagnostics screen shows, at one moment. Only values the device reported. */
data class RaceBoxDiagnosticsSnapshot(
    val info: RaceBoxDeviceInfo,
    val latest: RaceBoxData?,
    val power: RaceBoxPower?,
    /** Data messages in the last second. */
    val rateHz: Int,
    /** Data messages since the screen opened. */
    val messages: Long,
    /** Data messages missing from the stream, counted from gaps in the receiver's time of week. */
    val missed: Long,
    val checksumErrors: Long,
    val rssiDbm: Int?,
    val mtu: Int?,
    val gnssConfig: RaceBoxGnssConfig?,
    val memory: RaceBoxMemoryStatus?,
    /** True when the device answered a query with NACK (e.g. a command its firmware lacks). */
    val nackSeen: Boolean,
)

/**
 * Collects what a RaceBox reports for the diagnostics screen. Feed it raw notification bytes and
 * the other link facts; read [snapshot]. Pure Kotlin so it can be tested without a device.
 * Synchronized: notifications and GATT callbacks arrive on different threads.
 *
 * [isMicro] is the guess from the advertised name, used for the power byte until the device's
 * Model string arrives.
 */
class RaceBoxDiagnostics(private val isMicro: Boolean) {
    private val reader = UbxFrameReader()
    private var info = RaceBoxDeviceInfo()
    private var latest: RaceBoxData? = null
    private var messages = 0L
    private var missed = 0L
    private var lastItow: Long? = null
    private val steps = ArrayDeque<Long>()
    private val arrivals = ArrayDeque<Long>()
    private var rssi: Int? = null
    private var mtu: Int? = null
    private var gnssConfig: RaceBoxGnssConfig? = null
    private var memory: RaceBoxMemoryStatus? = null
    private var nack = false

    /** [nowMs] is a monotonic clock, used only for the message rate. */
    @Synchronized
    fun feed(bytes: ByteArray, nowMs: Long) {
        reader.feed(bytes) { frame ->
            if (frame.messageClass != RaceBoxClass) return@feed
            when (frame.messageId) {
                RaceBoxDataId -> RaceBoxData.parse(frame.payload)?.let { data(it, nowMs) }
                RaceBoxMessages.GNSS_CONFIG -> RaceBoxGnssConfig.parse(frame.payload)?.let { gnssConfig = it }
                RaceBoxMessages.RECORDING_STATUS -> RaceBoxMemoryStatus.parse(frame.payload)?.let { memory = it }
                RaceBoxMessages.NACK -> nack = true
            }
        }
    }

    private fun data(d: RaceBoxData, nowMs: Long) {
        messages++
        latest = d
        arrivals.addLast(nowMs)
        val previous = lastItow
        lastItow = d.iTowMs
        if (previous == null) return
        // iTOW wraps at the end of the GPS week; a step back resets the gap tracking.
        val step = d.iTowMs - previous
        if (step <= 0) return
        val usual = usualStep()
        if (usual != null && step > usual * 3 / 2) missed += (step + usual / 2) / usual - 1
        steps.addLast(step)
        if (steps.size > StepWindow) steps.removeFirst()
    }

    /** The median of recent steps: 40 ms at 25 Hz, more if standalone recording lowered the rate. */
    private fun usualStep(): Long? {
        if (steps.size < MinSteps) return null
        return steps.sorted()[steps.size / 2]
    }

    @Synchronized
    fun deviceInfo(edit: (RaceBoxDeviceInfo) -> RaceBoxDeviceInfo) {
        info = edit(info)
    }

    @Synchronized
    fun rssi(dbm: Int) {
        rssi = dbm
    }

    @Synchronized
    fun mtu(bytes: Int) {
        mtu = bytes
    }

    /** After a reconnect: drop a partial packet and restart gap tracking. Totals are kept. */
    @Synchronized
    fun reset() {
        reader.reset()
        lastItow = null
        steps.clear()
    }

    @Synchronized
    fun snapshot(nowMs: Long): RaceBoxDiagnosticsSnapshot {
        while (arrivals.isNotEmpty() && nowMs - arrivals.first() >= RateWindowMs) arrivals.removeFirst()
        val d = latest
        val micro = info.model?.startsWith(RaceBoxGatt.MICRO_PREFIX) ?: isMicro
        return RaceBoxDiagnosticsSnapshot(
            info = info,
            latest = d,
            power = d?.let { RaceBoxPower.of(it.batteryStatus, micro) },
            rateHz = arrivals.size,
            messages = messages,
            missed = missed,
            checksumErrors = reader.checksumErrors,
            rssiDbm = rssi,
            mtu = mtu,
            gnssConfig = gnssConfig,
            memory = memory,
            nackSeen = nack,
        )
    }

    private companion object {
        const val StepWindow = 25
        const val MinSteps = 5
        const val RateWindowMs = 1_000L
    }
}
