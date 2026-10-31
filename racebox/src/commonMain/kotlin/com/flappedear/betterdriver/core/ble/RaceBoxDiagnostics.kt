@file:Suppress("MagicNumber") // Byte offsets of the documented RaceBox reply payloads.

package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.core.JvmSynchronized

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
            val b = LittleEndian(payload)
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
    /** The saved motion-sensor calibration for this RaceBox, if any. */
    val calibration: ImuCalibration? = null,
    /** Samples taken so far while a calibration is being taken, or null when none is. */
    val capturing: Int? = null,
)

/**
 * Collects what a RaceBox reports for the diagnostics screen. Feed it raw notification bytes and
 * the other link facts; read [snapshot]. Common Kotlin (FBBD-371): the Android app's GATT link and
 * the iOS app's CoreBluetooth link feed the same probe. Synchronized on the JVM, where notifications
 * and GATT callbacks arrive on different threads; on iOS one serial queue feeds it.
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
    private var calibration: ImuCalibration? = null
    private var capture: MutableList<ImuSample>? = null

    /** [nowMs] is a monotonic clock, used only for the message rate. */
    @JvmSynchronized
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
        capture?.add(ImuSample(Vec3(d.accelXg, d.accelYg, d.accelZg), Vec3(d.gyroXdps, d.gyroYdps, d.gyroZdps)))
        arrivals.addLast(nowMs)
        val previous = lastItow
        lastItow = d.iTowMs
        if (previous == null) return
        // iTOW wraps at the end of the GPS week; a step back resets the gap tracking. A step over 5 s
        // is the clock jumping to GPS time when the fix comes, not messages lost.
        val step = d.iTowMs - previous
        if (step <= 0 || step > ClockJumpMs) return
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

    @JvmSynchronized
    fun deviceInfo(edit: (RaceBoxDeviceInfo) -> RaceBoxDeviceInfo) {
        info = edit(info)
    }

    @JvmSynchronized
    fun calibration(c: ImuCalibration?) {
        calibration = c
    }

    /** Starts collecting raw motion samples for a calibration. */
    @JvmSynchronized
    fun startCapture() {
        capture = mutableListOf()
    }

    /** Ends a capture and returns its samples (empty if none was running). */
    @JvmSynchronized
    fun endCapture(): List<ImuSample> = capture.orEmpty().also { capture = null }

    /** Link facts from GATT: signal strength in dBm, negotiated MTU in bytes. */
    @JvmSynchronized
    fun link(rssiDbm: Int? = null, mtuBytes: Int? = null) {
        rssiDbm?.let { rssi = it }
        mtuBytes?.let { mtu = it }
    }

    /** After a reconnect: drop a partial packet and restart gap tracking. Totals are kept. */
    @JvmSynchronized
    fun reset() {
        reader.reset()
        lastItow = null
        steps.clear()
    }

    @JvmSynchronized
    fun snapshot(nowMs: Long): RaceBoxDiagnosticsSnapshot {
        while (arrivals.isNotEmpty() && nowMs - arrivals.first() >= RateWindowMs) arrivals.removeFirst()
        val d = latest
        val micro = info.model?.startsWith(RaceBoxIds.MICRO_PREFIX) ?: isMicro
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
            calibration = calibration,
            capturing = capture?.size,
        )
    }

    private companion object {
        const val StepWindow = 25
        const val MinSteps = 5
        const val RateWindowMs = 1_000L
    }
}
