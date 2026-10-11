package com.flappedear.betterdriver.core.ble.nmea

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * NMEA receiver channels beyond the shared [GnssChannels]. Ids 200 to 299 are reserved for NMEA
 * receivers, so they cannot collide with the RaceBox (100 to 199) or other sources.
 */
object NmeaChannels {
    const val SOURCE = "nmea"

    /** GGA fix quality: 0 none, 1 GPS, 2 differential, 4 RTK fixed, 5 RTK float, 6 estimated. */
    val FIX_QUALITY = ChannelDef(200, "nmea_fix_quality", "", SOURCE, 0)

    /** GSA fix type: 1 none, 2 2D, 3 3D. */
    val FIX_TYPE = ChannelDef(201, "nmea_fix_type", "", SOURCE, 0)
    val HDOP = ChannelDef(202, "nmea_hdop", "", SOURCE, 2)
    val PDOP = ChannelDef(203, "nmea_pdop", "", SOURCE, 2)

    val ALL = listOf(FIX_QUALITY, FIX_TYPE, HDOP, PDOP)
}

/**
 * Motion channels of RaceChrono DIY `$RC2`/`$RC3` lines ([RcLine]), recorded like the RaceBox's
 * acceleration (g, 3 decimals) and rotation (deg/s, 2 decimals) but under their own ids, so a
 * RaceBox and a DIY device can be recorded together. Ids 500 to 509 (500 to 599 are kept for
 * RaceChrono DIY), though they arrive through the NMEA sources. Defined on the first such line, so
 * a plain GNSS receiver's session does not list them. The digital and analog fields are not
 * recorded yet.
 */
object RcChannels {
    val ACCEL_X = ChannelDef(500, "rc_accel_x", "g", NmeaChannels.SOURCE, 3)
    val ACCEL_Y = ChannelDef(501, "rc_accel_y", "g", NmeaChannels.SOURCE, 3)
    val ACCEL_Z = ChannelDef(502, "rc_accel_z", "g", NmeaChannels.SOURCE, 3)
    val GYRO_X = ChannelDef(503, "rc_gyro_x", "deg/s", NmeaChannels.SOURCE, 2)
    val GYRO_Y = ChannelDef(504, "rc_gyro_y", "deg/s", NmeaChannels.SOURCE, 2)
    val GYRO_Z = ChannelDef(505, "rc_gyro_z", "deg/s", NmeaChannels.SOURCE, 2)

    val ALL = listOf(ACCEL_X, ACCEL_Y, ACCEL_Z, GYRO_X, GYRO_Y, GYRO_Z)
}

/** Event kinds the NMEA source writes into the session. */
object NmeaEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "nmea_device"

    /** Text is the device address. */
    const val CONNECTED = "nmea_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "nmea_disconnected"

    /** The same kinds as the RaceBox, so session tools need not care which receiver was used. */
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"
}

private const val KmhPerKnot = 1.852
private const val MsPerDay = 86_400_000L
private const val HalfDayMs = MsPerDay / 2
private const val RcCountModulo = 65_536

/**
 * Turns the bytes of an NMEA receiver into sample sets, one per fix ([NmeaEpoch]), stamped with
 * the recorder clock when the fix's first sentence arrived. Position, speed, heading and altitude
 * are only included while there is a fix (RMC status A, GGA quality 1 to 6), so the VBO export
 * (one row per set with a position) never gets rows at 0°, 0°. Synchronized, so a reconnect can
 * [reset] it from another thread.
 *
 * RaceChrono DIY `$RC2`/`$RC3` lines (FBBD-115) become one sample set each, stamped on arrival,
 * with acceleration and rotation in [RcChannels], as RaceBox data messages are.
 */
class NmeaDecoder(private val sink: SampleSink) {
    private val parser = NmeaParser()
    private val clock = UtcDateTracker()
    private var hadFix: Boolean? = null
    private var rcDefined = false
    private var lastRcCount: Int? = null

    /** `$RC2`/`$RC3` lines missed, from gaps in their line counter. */
    @Volatile
    var rcMissed = 0L
        private set

    /** Fixes decoded since the decoder was created. */
    @Volatile
    var epochs = 0L
        private set

    val checksumErrors: Long get() = synchronized(this) { parser.checksumErrors }

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        NmeaChannels.ALL.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        val now = sink.nowMicros()
        parser.feed(bytes, now, onRcLine = { rcLine(it, now) }, onEpoch = ::epoch)
    }

    /** Drops a partial sentence and fix left from before a reconnect. */
    @Synchronized
    fun reset() {
        parser.reset()
        lastRcCount = null
    }

    private fun rcLine(line: RcLine, nowMicros: Long) {
        if (!rcDefined) {
            RcChannels.ALL.forEach(sink::defineChannel)
            rcDefined = true
        }
        val count = line.count
        val last = lastRcCount
        if (count != null && last != null) rcMissed += Math.floorMod(count - last - 1, RcCountModulo)
        lastRcCount = count
        val values = listOf(
            RcChannels.ACCEL_X to line.accelXg,
            RcChannels.ACCEL_Y to line.accelYg,
            RcChannels.ACCEL_Z to line.accelZg,
            RcChannels.GYRO_X to line.gyroXdps,
            RcChannels.GYRO_Y to line.gyroYdps,
            RcChannels.GYRO_Z to line.gyroZdps,
        ).mapNotNull { (def, v) -> v?.let { ChannelValue(def.id, it) } }
        if (values.isNotEmpty()) sink.submit(SampleSet(nowMicros, values))
    }

    private fun epoch(e: NmeaEpoch) {
        epochs++
        val position = position(e)
        val hasFix = position != null
        if (hasFix != hadFix) {
            if (hadFix != null || hasFix) {
                val kind = if (hasFix) NmeaEvents.FIX_ACQUIRED else NmeaEvents.FIX_LOST
                sink.event(kind, "sats=${e.gga?.satellites ?: "?"}")
            }
            hadFix = hasFix
        }
        val values = values(e, clock.utcMillis(e), position)
        if (values.isNotEmpty()) sink.submit(SampleSet(e.arrivalMicros, values))
    }

    /**
     * Latitude and longitude if the fix is valid. Every sentence present must say so: a receiver
     * can report RMC status A while GGA already says no fix, or the other way round.
     */
    private fun position(e: NmeaEpoch): Pair<Double, Double>? {
        val rmc = e.rmc
        val gga = e.gga
        val valid = (rmc != null || gga != null) && (rmc?.valid ?: true) && (gga?.hasFix ?: true)
        val lat = rmc?.latDeg ?: gga?.latDeg
        val lon = rmc?.lonDeg ?: gga?.lonDeg
        return if (valid && lat != null && lon != null) lat to lon else null
    }

    private fun values(e: NmeaEpoch, utcMs: Long?, position: Pair<Double, Double>?) = buildList {
        utcMs?.let { add(ChannelValue(GnssChannels.UTC_MS.id, it.toDouble())) }
        e.gga?.satellites?.let { add(ChannelValue(GnssChannels.SATS.id, it.toDouble())) }
        e.gga?.let { add(ChannelValue(NmeaChannels.FIX_QUALITY.id, it.quality.toDouble())) }
        e.gsa?.fixType?.let { add(ChannelValue(NmeaChannels.FIX_TYPE.id, it.toDouble())) }
        (e.gga?.hdop ?: e.gsa?.hdop)?.let { add(ChannelValue(NmeaChannels.HDOP.id, it)) }
        e.gsa?.pdop?.let { add(ChannelValue(NmeaChannels.PDOP.id, it)) }
        if (position == null) return@buildList
        add(ChannelValue(GnssChannels.LAT.id, position.first))
        add(ChannelValue(GnssChannels.LON.id, position.second))
        val speed = e.rmc?.speedKnots?.times(KmhPerKnot) ?: e.vtg?.speedKmh
        speed?.let { add(ChannelValue(GnssChannels.SPEED_KMH.id, it)) }
        // Many receivers leave the course empty when standing still; the export then keeps the last one.
        (e.rmc?.courseDeg ?: e.vtg?.courseDeg)?.let { add(ChannelValue(GnssChannels.HEADING.id, it)) }
        e.gga?.altitudeM?.let { add(ChannelValue(GnssChannels.ALT_M.id, it)) }
    }
}

/**
 * UTC from fix times. Only RMC has the date; a fix without one (GGA alone, or RMC with an empty
 * date) uses the last date seen, moved on by a day when the time of day jumps back by more than
 * half a day, i.e. midnight passed. With no date at all there is no UTC time; the VBO export then
 * falls back to the session clock.
 */
internal class UtcDateTracker {
    private var epochDay: Long? = null
    private var lastTimeOfDayMs: Long? = null

    fun utcMillis(e: NmeaEpoch): Long? {
        val time = e.timeOfDayMs ?: return null
        val rmcDay = e.rmc?.epochDay
        val last = lastTimeOfDayMs
        epochDay = when {
            rmcDay != null -> rmcDay
            last != null && time < last - HalfDayMs -> epochDay?.plus(1)
            else -> epochDay
        }
        lastTimeOfDayMs = time
        return epochDay?.let { it * MsPerDay + time }
    }
}
