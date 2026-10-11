package com.flappedear.betterdriver.core.ble.lns

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * LNS receiver channels beyond the shared [GnssChannels]. Ids 300 to 399 are reserved for LNS
 * receivers, so they cannot collide with the RaceBox (100 to 199) or NMEA receivers (200 to 299).
 */
object LnsChannels {
    const val SOURCE = "lns"

    /** Position Status: 0 no position, 1 OK, 2 estimated, 3 last known ([LnsPositionStatus]). */
    val POSITION_STATUS = ChannelDef(300, "lns_position_status", "", SOURCE, 0)

    /** The receiver's own odometer, 1/10 m resolution; wraps at 1,677,721.5 m. */
    val TOTAL_DISTANCE = ChannelDef(301, "lns_total_distance", "m", SOURCE, 1)

    /** Heading from a magnetic compass (Heading Source 1), kept apart from the course in gnss_heading. */
    val COMPASS_HEADING = ChannelDef(302, "lns_compass_heading", "deg", SOURCE, 2)

    val ALL = listOf(POSITION_STATUS, TOTAL_DISTANCE, COMPASS_HEADING)
}

/** Event kinds the LNS source writes into the session. */
object LnsEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "lns_device"

    /** Text is the device address. */
    const val CONNECTED = "lns_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "lns_disconnected"

    /** The same kinds as the RaceBox and NMEA receivers, so session tools need not care which was used. */
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"
}

private const val KmhPerMs = 3.6

/**
 * Turns Location and Speed notifications into sample sets, one per notification, stamped with
 * the recorder clock on arrival. Synchronized, so a reconnect can [reset] it from another thread.
 *
 * Fix: decided by notifications that carry a location. Position Status OK or Estimated is a fix,
 * Last Known Position is not. No Position (0) with coordinates other than exactly 0°, 0° also
 * counts as a fix, because a server without the Position Status feature leaves those bits at 0.
 * Position, speed, course and elevation are only included while there is a fix, so the VBO
 * export never gets rows at 0°, 0°. A notification without a location (a server may split one
 * value over several notifications when it does not fit the MTU) keeps the last fix state.
 *
 * Heading goes in gnss_heading only when it comes from movement; a compass heading goes in
 * [LnsChannels.COMPASS_HEADING]. UTC time goes in gnss_utc_ms whenever it is known; the
 * Position Status goes in [LnsChannels.POSITION_STATUS] with every location.
 */
class LnsDecoder(private val sink: SampleSink) {
    private var hasFix: Boolean? = null

    /** Values decoded since the decoder was created. */
    @Volatile
    var decoded = 0L
        private set

    /** Values dropped because they were shorter than their flags say. */
    @Volatile
    var truncated = 0L
        private set

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        LnsChannels.ALL.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        val arrival = sink.nowMicros()
        val v = LocationAndSpeed.parse(bytes)
        if (v == null) {
            truncated++
            return
        }
        decoded++
        if (v.hasLocation) fixChanged(isFix(v), v.positionStatus)
        val out = channelValues(v, hasFix == true)
        if (out.isNotEmpty()) sink.submit(SampleSet(arrival, out))
    }

    /** Forgets the fix state from before a reconnect. */
    @Synchronized
    fun reset() {
        hasFix = null
    }

    private fun isFix(v: LocationAndSpeed): Boolean = when (v.positionStatus) {
        LnsPositionStatus.OK, LnsPositionStatus.ESTIMATED -> true
        LnsPositionStatus.NO_POSITION -> v.latDeg != 0.0 || v.lonDeg != 0.0
        else -> false
    }

    private fun fixChanged(fix: Boolean, status: Int) {
        val had = hasFix
        hasFix = fix
        if (fix == had || (had == null && !fix)) return
        sink.event(if (fix) LnsEvents.FIX_ACQUIRED else LnsEvents.FIX_LOST, "status=$status")
    }

    private fun channelValues(v: LocationAndSpeed, fix: Boolean) = buildList {
        v.utcMillis?.let { add(ChannelValue(GnssChannels.UTC_MS.id, it.toDouble())) }
        if (v.hasLocation) add(ChannelValue(LnsChannels.POSITION_STATUS.id, v.positionStatus.toDouble()))
        v.totalDistanceM?.let { add(ChannelValue(LnsChannels.TOTAL_DISTANCE.id, it)) }
        if (v.headingFromCompass) v.headingDeg?.let { add(ChannelValue(LnsChannels.COMPASS_HEADING.id, it)) }
        if (!fix) return@buildList
        if (v.hasLocation) {
            add(ChannelValue(GnssChannels.LAT.id, checkNotNull(v.latDeg)))
            add(ChannelValue(GnssChannels.LON.id, checkNotNull(v.lonDeg)))
        }
        v.speedMs?.let { add(ChannelValue(GnssChannels.SPEED_KMH.id, it * KmhPerMs)) }
        if (!v.headingFromCompass) v.headingDeg?.let { add(ChannelValue(GnssChannels.HEADING.id, it)) }
        v.elevationM?.let { add(ChannelValue(GnssChannels.ALT_M.id, it)) }
    }
}
