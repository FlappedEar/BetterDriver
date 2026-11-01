package com.flappedear.betterdriver.core.ble.tpms

import com.flappedear.betterdriver.core.JvmSynchronized
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.logLink
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * The FlappedEar TPMS bridge (https://github.com/FlappedEar/RCTPMS, FBBD-224): an ESP32 that hears
 * the four BLE tyre sensors (LYTPMS Type D) and offers their values as a RaceChrono DIY device,
 * service 0x1FF8. Its CAN-Bus main characteristic [VALUES] notifies one value per notification; it
 * sends nothing until a filter is written to [FILTER], and the filter is cleared on every connect.
 */
object TpmsIds {
    /** RaceChrono DIY BLE service, as a 16-bit Bluetooth number. */
    const val SERVICE = "1FF8"

    /** CAN-Bus main: READ and NOTIFY, a 32-bit little-endian PID and its payload. */
    const val VALUES = "0001"

    /** CAN-Bus filter: WRITE. */
    const val FILTER = "0002"

    /**
     * The filter written after each connect: allow every PID (command 1), at most one value per
     * PID every 1,000 ms (0x03E8, big-endian), the bridge's own fastest rate.
     */
    val AllowAllEverySecond: ByteArray get() = byteArrayOf(0x01, 0x03, 0xE8.toByte())

    /** The bridge advertises "RC TPMS AMOLED"; a DIY GPS device on the same service does not. */
    const val NAME_PREFIX = "RC TPMS"

    /** Whether an advertised [name] is a TPMS bridge rather than a RaceChrono DIY GPS device. */
    fun isBridge(name: String?): Boolean = name?.trim()?.startsWith(NAME_PREFIX, ignoreCase = true) == true
}

/**
 * Tyre channels, front left, front right, rear left, rear right. Ids 800 to 819 are reserved for
 * tyre sensors. The names are the ones FlappedEar Overlays reads (FBBD-223), in bar and °C.
 */
object TpmsChannels {
    const val SOURCE = "tpms"

    val PRESSURE_FL = ChannelDef(800, "tyre_pressure_fl", "bar", SOURCE, 2)
    val PRESSURE_FR = ChannelDef(801, "tyre_pressure_fr", "bar", SOURCE, 2)
    val PRESSURE_RL = ChannelDef(802, "tyre_pressure_rl", "bar", SOURCE, 2)
    val PRESSURE_RR = ChannelDef(803, "tyre_pressure_rr", "bar", SOURCE, 2)
    val TEMP_FL = ChannelDef(804, "tyre_temp_fl", "°C", SOURCE, 0)
    val TEMP_FR = ChannelDef(805, "tyre_temp_fr", "°C", SOURCE, 0)
    val TEMP_RL = ChannelDef(806, "tyre_temp_rl", "°C", SOURCE, 0)
    val TEMP_RR = ChannelDef(807, "tyre_temp_rr", "°C", SOURCE, 0)

    val PRESSURES = listOf(PRESSURE_FL, PRESSURE_FR, PRESSURE_RL, PRESSURE_RR)
    val TEMPS = listOf(TEMP_FL, TEMP_FR, TEMP_RL, TEMP_RR)
    val ALL = PRESSURES + TEMPS

    /** "FL", "FR", "RL", "RR" by wheel index. */
    val WHEELS = listOf("FL", "FR", "RL", "RR")
}

/** Event kinds the TPMS bridge writes into the session. */
object TpmsEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "tpms_device"

    /** Text is the device address. */
    const val CONNECTED = "tpms_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "tpms_disconnected"

    /** A wheel's pressure and temperature stopped changing (the bridge repeats its last reading). Text "wheel=FL". */
    const val STALE = "tpms_stale"

    /** A stale wheel's reading changed again. Text "wheel=FL". */
    const val FRESH = "tpms_fresh"
}

/** One decoded notification: [wheel] 0 to 3 (front left to rear right), a pressure in bar or a temperature in °C. */
data class TpmsValue(val wheel: Int, val pressure: Boolean, val value: Double) {
    val channel: ChannelDef get() = if (pressure) TpmsChannels.PRESSURES[wheel] else TpmsChannels.TEMPS[wheel]
}

private const val PidBytes = 4
private const val TempBytes = PidBytes + 1
private const val FirstPressurePid = 1L
private const val LastPressurePid = 4L
private const val FirstTempPid = 8L
private const val LastTempPid = 11L
private const val CentibarPerBar = 100.0
private const val ByteMask = 0xFF
private const val ByteBits = 8

/**
 * One CAN-Bus main notification of the bridge, as RCTPMS's encodePacket writes it: a 32-bit
 * little-endian PID, then for PIDs 1 to 4 the pressure in hundredths of a bar as a big-endian
 * unsigned 16-bit number (01 2E = 3.02 bar), for PIDs 8 to 11 the temperature as an unsigned byte
 * in °C (the bridge sends 0 for anything below). Null for any other PID or a short value.
 */
fun parseTpmsValue(bytes: ByteArray): TpmsValue? {
    var pid = -1L
    if (bytes.size >= TempBytes) {
        pid = 0L
        for (i in 0 until PidBytes) pid = pid or ((bytes[i].toLong() and ByteMask.toLong()) shl (ByteBits * i))
    }
    val first = bytes.getOrNull(PidBytes)?.toInt()?.and(ByteMask)
    val second = bytes.getOrNull(PidBytes + 1)?.toInt()?.and(ByteMask)
    return when {
        first == null -> null
        pid in FirstPressurePid..LastPressurePid && second != null -> {
            val centibar = (first shl ByteBits) or second
            TpmsValue((pid - FirstPressurePid).toInt(), pressure = true, value = centibar / CentibarPerBar)
        }
        pid in FirstTempPid..LastTempPid ->
            TpmsValue((pid - FirstTempPid).toInt(), pressure = false, value = first.toDouble())
        else -> null
    }
}

/**
 * A wheel whose pressure and temperature have not changed for this long is stale: the bridge keeps
 * repeating a sensor's last reading after it goes quiet, and marks it amber after the same 5 min.
 */
const val StaleAfterMicros = 300_000_000L

/** A wheel's latest values for a status display; null where the bridge has sent none yet. */
data class TyreReading(val bar: Double?, val celsius: Double?, val stale: Boolean)

/**
 * Turns the bridge's notifications into sample sets, one value per set stamped on arrival (the
 * bridge sends each of its eight values about once a second). A wheel whose pressure and
 * temperature both stayed the same for [StaleAfterMicros] is stale: its repeats are left out, so a
 * dead sensor does not record a flat line, until either value changes again. Each change of a
 * wheel's state is an event. Synchronized on the JVM, so a reconnect can [reset] it from another
 * thread; on iOS one queue makes every call.
 */
class TpmsDecoder(private val sink: SampleSink) {
    private class Wheel {
        var bar: Double? = null
        var celsius: Double? = null
        var changedMicros = 0L
        var stale = false
    }

    private val wheels = List(TpmsChannels.WHEELS.size) { Wheel() }

    /** Notifications that were not a tyre value (another PID, too short). */
    var ignored = 0L
        private set

    /** Values recorded since the decoder was created. */
    var recorded = 0L
        private set

    fun defineChannels() {
        TpmsChannels.ALL.forEach(sink::defineChannel)
    }

    @JvmSynchronized
    fun feed(bytes: ByteArray) {
        val v = parseTpmsValue(bytes)
        if (v == null) {
            ignored++
            return
        }
        val now = sink.nowMicros()
        val w = wheels[v.wheel]
        val before = if (v.pressure) w.bar else w.celsius
        if (v.pressure) w.bar = v.value else w.celsius = v.value
        val name = TpmsChannels.WHEELS[v.wheel]
        if (before != v.value) {
            w.changedMicros = now
            if (w.stale) {
                w.stale = false
                sink.event(TpmsEvents.FRESH, "wheel=$name")
            }
        } else if (!w.stale && now - w.changedMicros >= StaleAfterMicros) {
            w.stale = true
            sink.event(TpmsEvents.STALE, "wheel=$name")
        }
        if (w.stale) return
        recorded++
        sink.submit(SampleSet(now, listOf(ChannelValue(v.channel.id, v.value))))
    }

    /** The four wheels' latest values, front left to rear right. */
    @JvmSynchronized
    fun readings(): List<TyreReading> = wheels.map { TyreReading(it.bar, it.celsius, it.stale) }

    /**
     * After a drop: the next values start each wheel's staleness count afresh, as the bridge may
     * have heard its sensors while the phone was away.
     */
    @JvmSynchronized
    fun reset() {
        val now = sink.nowMicros()
        wheels.forEach { it.changedMicros = now }
    }
}

/**
 * What a TPMS bridge link does to a recording, the same on Android (TpmsSource over its GATT link)
 * and iOS (CoreBluetooth), FBBD-224: define the eight tyre channels, log the bridge, decode each
 * notification, and log connects and drops. The platform link writes [TpmsIds.AllowAllEverySecond]
 * to the filter after each connect, reports its state to [linkChanged] and passes every
 * notification of [TpmsIds.VALUES] to [feed]. The bridge never stops the session or the GPS.
 *
 * [address] names the bridge in the session (Android's Bluetooth address, the iOS identifier) and
 * [deviceName] is its advertised name.
 */
class TpmsSession(
    private val sink: SampleSink,
    private val address: String,
    private val deviceName: String,
) {
    private val decoder = TpmsDecoder(sink)

    /** Whether data flowed since the last drop; a drop before any data is not a disconnect. */
    private var wasStreaming = false
    private var loggedStreaming = false

    fun start() {
        decoder.defineChannels()
        sink.event(TpmsEvents.DEVICE, "address=$address name=$deviceName")
    }

    fun feed(bytes: ByteArray) = decoder.feed(bytes)

    /**
     * The bridge link's new state [s], from the shared notify link on either platform: logged, and
     * connects and drops as events.
     */
    fun linkChanged(s: LinkState) {
        logLink(address, s, loggedStreaming)
        when (s) {
            is LinkState.Streaming -> {
                if (!wasStreaming) sink.event(TpmsEvents.CONNECTED, address)
                wasStreaming = true
            }
            is LinkState.Reconnecting -> {
                if (wasStreaming) sink.event(TpmsEvents.DISCONNECTED, s.reason)
                wasStreaming = false
                decoder.reset()
            }
            else -> Unit
        }
        if (s is LinkState.Streaming || s is LinkState.Reconnecting) loggedStreaming = s is LinkState.Streaming
    }

    /** The four wheels' latest values, front left to rear right. */
    fun readings(): List<TyreReading> = decoder.readings()
}
