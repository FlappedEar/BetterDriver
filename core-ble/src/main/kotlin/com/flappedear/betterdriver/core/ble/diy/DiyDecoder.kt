package com.flappedear.betterdriver.core.ble.diy

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink
import java.util.UUID

/**
 * RaceChrono DIY BLE channels beyond the shared [GnssChannels]. Ids 510 to 519; 500 to 599 are
 * kept for RaceChrono DIY (RC2/RC3 lines use 500 to 509), so they cannot collide with the RaceBox
 * (100 to 199), NMEA receivers (200 to 299), LNS (300 to 399) or heart rate (400 to 499).
 */
object DiyChannels {
    const val SOURCE = "racechrono_diy"

    /** The 2-bit fix quality of the GPS main characteristic; 0 is no fix. */
    val FIX_QUALITY = ChannelDef(510, "diy_fix_quality", "", SOURCE, 0)
    val HDOP = ChannelDef(511, "diy_hdop", "", SOURCE, 1)
    val VDOP = ChannelDef(512, "diy_vdop", "", SOURCE, 1)

    val ALL = listOf(FIX_QUALITY, HDOP, VDOP)
}

/** Event kinds the RaceChrono DIY BLE source writes into the session. */
object DiyEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "diy_device"

    /** Text is the device address. */
    const val CONNECTED = "diy_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "diy_disconnected"

    /** The same kinds as the RaceBox and NMEA receivers. */
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"
}

/** How long a GPS main value waits for the GPS time with its sync bits before it goes without UTC. */
private const val MaxTimeWaitMicros = 1_000_000L

/** How often the GPS time is read again while it is missing. */
private const val TimeRequestIntervalMicros = 1_000_000L

/**
 * Turns RaceChrono DIY BLE GPS notifications into sample sets (FBBD-118), one per GPS main value,
 * stamped with the recorder clock on arrival. Position, speed, heading and altitude only with a
 * fix, as for the other receivers.
 *
 * UTC needs both characteristics: the GPS main value has the time within the hour, the GPS time
 * value the date and hour, and they belong together when their sync bits match. As the API
 * README says, the client waits for the other one to update when they differ (around the turn of
 * the hour, and at connect). So a main value without its time is held for up to 1 s and passed on
 * without UTC after that; meanwhile [requestTime] is called (at most once a second) so the source
 * reads the GPS time characteristic, which devices need not notify. The last two time values are
 * kept, so main values still in flight with the previous sync bits find theirs.
 *
 * Synchronized: notifications arrive on binder threads, reads on the link's thread.
 */
class DiyGpsDecoder(private val sink: SampleSink, private val requestTime: () -> Unit = {}) {
    private class Pending(val main: DiyGpsMain, val arrivalMicros: Long)

    private val times = ArrayDeque<DiyGpsTime>()
    private val pending = ArrayDeque<Pending>()
    private var hadFix: Boolean? = null
    private var lastRequestMicros: Long? = null

    /** GPS main values decoded since the decoder was created. */
    @Volatile
    var fixes = 0L
        private set

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        DiyChannels.ALL.forEach(sink::defineChannel)
    }

    /** A notification or read result of [uuid]. Other characteristics are ignored. */
    @Synchronized
    fun feed(uuid: UUID, bytes: ByteArray) {
        val now = sink.nowMicros()
        when (uuid) {
            DiyGatt.GPS_MAIN -> parseDiyGpsMain(bytes)?.let { pending.addLast(Pending(it, now)) }
            DiyGatt.GPS_TIME -> parseDiyGpsTime(bytes)?.let(::addTime)
        }
        drain(now)
    }

    /**
     * After a drop: passes on held main values without UTC and forgets the time values, as the
     * sync bits may have moved on before the reconnect.
     */
    @Synchronized
    fun reset() {
        while (pending.isNotEmpty()) submit(pending.removeFirst(), null)
        times.clear()
        lastRequestMicros = null
    }

    private fun addTime(t: DiyGpsTime) {
        times.removeAll { it.sync == t.sync }
        times.addLast(t)
        if (times.size > 2) times.removeFirst()
    }

    /** Passes on held values in order, while each has its time or has waited long enough. */
    private fun drain(now: Long) {
        while (pending.isNotEmpty()) {
            val p = pending.first()
            val time = times.lastOrNull { it.sync == p.main.sync }
            if (time == null && now - p.arrivalMicros < MaxTimeWaitMicros) break
            pending.removeFirst()
            submit(p, time)
        }
        val lastRequest = lastRequestMicros
        if (pending.isNotEmpty() && (lastRequest == null || now - lastRequest >= TimeRequestIntervalMicros)) {
            lastRequestMicros = now
            requestTime()
        }
    }

    private fun submit(p: Pending, time: DiyGpsTime?) {
        fixes++
        val m = p.main
        if (m.hasFix != hadFix) {
            if (hadFix != null || m.hasFix) {
                sink.event(if (m.hasFix) DiyEvents.FIX_ACQUIRED else DiyEvents.FIX_LOST, "sats=${m.satellites ?: "?"}")
            }
            hadFix = m.hasFix
        }
        val hourStart = time?.hourStartUtcMs
        val inHour = m.timeInHourMs
        val utc = if (hourStart != null && inHour != null) hourStart + inHour else null
        sink.submit(SampleSet(p.arrivalMicros, values(m, utc)))
    }

    private fun values(m: DiyGpsMain, utcMs: Long?): List<ChannelValue> = buildList {
        utcMs?.let { add(ChannelValue(GnssChannels.UTC_MS.id, it.toDouble())) }
        m.satellites?.let { add(ChannelValue(GnssChannels.SATS.id, it.toDouble())) }
        add(ChannelValue(DiyChannels.FIX_QUALITY.id, m.fixQuality.toDouble()))
        m.hdop?.let { add(ChannelValue(DiyChannels.HDOP.id, it)) }
        m.vdop?.let { add(ChannelValue(DiyChannels.VDOP.id, it)) }
        val lat = m.latDeg
        val lon = m.lonDeg
        if (!m.hasFix || lat == null || lon == null) return@buildList
        add(ChannelValue(GnssChannels.LAT.id, lat))
        add(ChannelValue(GnssChannels.LON.id, lon))
        m.speedKmh?.let { add(ChannelValue(GnssChannels.SPEED_KMH.id, it)) }
        m.bearingDeg?.let { add(ChannelValue(GnssChannels.HEADING.id, it)) }
        m.altitudeM?.let { add(ChannelValue(GnssChannels.ALT_M.id, it)) }
    }
}
