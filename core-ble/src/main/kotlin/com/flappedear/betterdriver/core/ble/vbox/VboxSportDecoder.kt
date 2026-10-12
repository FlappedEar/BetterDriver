package com.flappedear.betterdriver.core.ble.vbox

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * VBOX Sport channels beyond the shared [GnssChannels]. Ids 600 to 619 are reserved for the VBOX
 * Sport (600 to 699 is shared with the Dragy, which uses 620 to 639).
 */
object VboxSportChannels {
    const val SOURCE = "vbox_sport"

    /** 1 while the receiver reports DGPS in use (bit 7 of the satellites byte), else 0. */
    val DGPS = ChannelDef(600, "vbox_dgps", "", SOURCE, 0)
    val HDOP = ChannelDef(601, "vbox_hdop", "", SOURCE, 2)

    val ALL = listOf(DGPS, HDOP)
}

/** Event kinds the VBOX Sport source writes into the session. */
object VboxSportEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "vbox_sport_device"

    /** Text is the device address. */
    const val CONNECTED = "vbox_sport_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "vbox_sport_disconnected"

    /**
     * Data arrives but no message has passed its CRC yet: written once per connection after
     * [BadMessagesBeforeEvent] failures. On a real device this means the byte order or CRC range
     * assumed in [VboxSportFormat] is wrong (unverified on hardware). Text holds the counts.
     */
    const val NO_VALID_MESSAGES = "vbox_sport_no_valid_messages"

    /** The same kinds as the other receivers. */
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"
}

/** Failed messages (CRC or undocumented channels) without a good one before [VboxSportEvents.NO_VALID_MESSAGES]. */
const val BadMessagesBeforeEvent = 20L

/**
 * One VBOX Sport message in recorder units. A field is null when the message does not carry it.
 * [timeOfDayMs] is UTC since midnight; the stream has no date. [altitudeM] is the height above the
 * WGS84 ellipsoid, not above mean sea level as NMEA and RaceBox report.
 */
data class VboxSportFix(
    val satellites: Int?,
    val dgps: Boolean,
    val timeOfDayMs: Long?,
    val latDeg: Double?,
    val lonDeg: Double?,
    val speedKmh: Double?,
    val headingDeg: Double?,
    val altitudeM: Double?,
    val hdop: Double?,
) {
    /**
     * The stream has no fix flag, so a position counts as a fix with at least 3 satellites (the
     * fewest for a 2D fix) and coordinates other than exactly 0°, 0°. A design choice, not from the
     * format document.
     */
    val hasFix: Boolean
        get() = (satellites ?: 0) >= MinFixSatellites && latDeg != null && lonDeg != null &&
            !(latDeg == 0.0 && lonDeg == 0.0)

    companion object {
        private const val MinFixSatellites = 3
        private const val SatCountMask = 0x7F
        private const val DgpsBit = 0x80
        private const val MsPerTick = 10L
        private const val MinutesScale = 100_000.0 * 60.0
        private const val Hundredths = 100.0
        private const val KmhPerKnot = 1.852

        /**
         * Decodes the channels the recorder uses; scales and signs are from [VboxSportFormat]'s
         * sources, the byte order is assumed big-endian (see there). Vertical velocity and the GPS
         * accelerations are left out: the format gives no scale for vertical velocity ("m/s") and
         * no signedness for the accelerations. Battery and media channels are not needed.
         */
        fun from(m: VboxSportMessage): VboxSportFix {
            val s = m.standard
            fun at(bit: Int) = VboxSportFormat.standardOffset(s, bit)
            val sats = at(VboxSportFormat.SATELLITES)?.let { m.u8(it) }
            return VboxSportFix(
                satellites = sats?.and(SatCountMask),
                dgps = sats != null && sats and DgpsBit != 0,
                timeOfDayMs = at(VboxSportFormat.TIME)?.let { m.u24(it) * MsPerTick },
                // Minutes × 100,000; positive north.
                latDeg = at(VboxSportFormat.LATITUDE)?.let { m.i32(it) / MinutesScale },
                // Minutes × 100,000; positive WEST, so negated for east-positive degrees.
                lonDeg = at(VboxSportFormat.LONGITUDE)?.let { -m.i32(it) / MinutesScale },
                speedKmh = at(VboxSportFormat.SPEED)?.let { m.u16(it) / Hundredths * KmhPerKnot },
                headingDeg = at(VboxSportFormat.HEADING)?.let { m.u16(it) / Hundredths },
                // "True signed 24 bit number", metres × 100.
                altitudeM = at(VboxSportFormat.HEIGHT)?.let { m.i24(it) / Hundredths },
                hdop = VboxSportFormat.extendedOffset(s, m.extended, VboxSportFormat.EXT_HDOP)
                    ?.let { m.u16(it) / Hundredths },
            )
        }
    }
}

private const val ByteMask = 0xFF
private const val Sign24 = 0x80_0000
private const val Range24 = 0x100_0000
private const val Byte1 = 8
private const val Byte2 = 16

// Big-endian; see VboxSportFormat for why.
private fun VboxSportMessage.u8(i: Int) = data[i].toInt() and ByteMask
private fun VboxSportMessage.u16(i: Int) = (u8(i) shl Byte1) or u8(i + 1)
private fun VboxSportMessage.u24(i: Int) = (u8(i) shl Byte2) or u16(i + 1)
private fun VboxSportMessage.i24(i: Int) = u24(i).let { if (it and Sign24 != 0) it - Range24 else it }
private fun VboxSportMessage.i32(i: Int) = (u16(i) shl Byte2) or u16(i + 2)

private const val MsPerDay = 86_400_000L
private const val HalfDayMs = MsPerDay / 2
private const val MicrosPerMs = 1_000L

/** Messages buffered longer than this before one read are stamped on arrival, not by their GNSS time. */
private const val MaxBufferedMs = 5_000L

/**
 * UTC from a time of day without a date: the instant with that time of day closest to [nowUtcMs]
 * (the phone's clock). Right as long as the phone's clock is within 12 hours of GNSS time.
 */
fun utcFromTimeOfDay(timeOfDayMs: Long, nowUtcMs: Long): Long {
    val sameDay = Math.floorDiv(nowUtcMs, MsPerDay) * MsPerDay + timeOfDayMs
    return when {
        sameDay - nowUtcMs > HalfDayMs -> sameDay - MsPerDay
        nowUtcMs - sameDay > HalfDayMs -> sameDay + MsPerDay
        else -> sameDay
    }
}

/**
 * Turns the bytes of a VBOX Sport into sample sets, one per message, stamped with the recorder
 * clock on arrival. When one read holds several messages (Bluetooth buffered them), the last is
 * stamped on arrival and each one before it as much earlier as its GNSS time says, so every set
 * still has its own, increasing time and the lap timer does not skip any. Position, speed, heading,
 * altitude and UTC are only included with a fix ([VboxSportFix.hasFix]), so the VBO export never
 * gets rows at 0°, 0°. UTC takes its date from [utcNow] (the phone's clock; see
 * [utcFromTimeOfDay]). Synchronized, so a reconnect can [reset] it from another thread.
 */
class VboxSportDecoder(private val sink: SampleSink, private val utcNow: () -> Long = System::currentTimeMillis) {
    private val reader = VboxSportFrameReader()
    private var hadFix: Boolean? = null
    private var goodSinceReset = false
    private var reportedBad = false
    private var badAtReset = 0L
    private var lastStamp: Long? = null

    /** Messages decoded since the decoder was created. */
    @Volatile
    var messages = 0L
        private set

    val checksumErrors: Long get() = synchronized(this) { reader.checksumErrors }

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        VboxSportChannels.ALL.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        val now = sink.nowMicros()
        val fixes = buildList { reader.feed(bytes) { add(VboxSportFix.from(it)) } }
        val lastTime = fixes.lastOrNull()?.timeOfDayMs
        for (f in fixes) {
            val ageMs = f.timeOfDayMs?.let { t -> lastTime?.let { Math.floorMod(it - t, MsPerDay) } }
            val t = now - (ageMs?.takeIf { it <= MaxBufferedMs } ?: 0L) * MicrosPerMs
            // Never at or before the set before it, whatever the clocks say.
            val stamp = lastStamp?.let { maxOf(t, it + 1) } ?: t
            lastStamp = stamp
            message(f, stamp)
        }
        val bad = reader.checksumErrors + reader.unsupportedMessages - badAtReset
        if (!goodSinceReset && !reportedBad && bad >= BadMessagesBeforeEvent) {
            reportedBad = true
            val text = "crc_errors=${reader.checksumErrors} unsupported=${reader.unsupportedMessages}"
            sink.event(VboxSportEvents.NO_VALID_MESSAGES, text)
        }
    }

    /** Drops a partial message left from before a reconnect. */
    @Synchronized
    fun reset() {
        reader.reset()
        goodSinceReset = false
        reportedBad = false
        badAtReset = reader.checksumErrors + reader.unsupportedMessages
    }

    private fun message(f: VboxSportFix, tMicros: Long) {
        messages++
        goodSinceReset = true
        if (f.hasFix != hadFix) {
            if (hadFix != null || f.hasFix) {
                val kind = if (f.hasFix) VboxSportEvents.FIX_ACQUIRED else VboxSportEvents.FIX_LOST
                sink.event(kind, "sats=${f.satellites ?: "?"}")
            }
            hadFix = f.hasFix
        }
        sink.submit(SampleSet(tMicros, values(f)))
    }

    private fun values(f: VboxSportFix) = buildList {
        f.satellites?.let { add(ChannelValue(GnssChannels.SATS.id, it.toDouble())) }
        if (f.satellites != null) add(ChannelValue(VboxSportChannels.DGPS.id, if (f.dgps) 1.0 else 0.0))
        f.hdop?.let { add(ChannelValue(VboxSportChannels.HDOP.id, it)) }
        if (!f.hasFix) return@buildList
        f.timeOfDayMs?.let { add(ChannelValue(GnssChannels.UTC_MS.id, utcFromTimeOfDay(it, utcNow()).toDouble())) }
        add(ChannelValue(GnssChannels.LAT.id, checkNotNull(f.latDeg)))
        add(ChannelValue(GnssChannels.LON.id, checkNotNull(f.lonDeg)))
        f.speedKmh?.let { add(ChannelValue(GnssChannels.SPEED_KMH.id, it)) }
        f.headingDeg?.let { add(ChannelValue(GnssChannels.HEADING.id, it)) }
        f.altitudeM?.let { add(ChannelValue(GnssChannels.ALT_M.id, it)) }
    }
}
