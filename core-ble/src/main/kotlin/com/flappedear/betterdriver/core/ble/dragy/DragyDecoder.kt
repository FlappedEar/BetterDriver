package com.flappedear.betterdriver.core.ble.dragy

import com.flappedear.betterdriver.core.ble.UbxFrameReader
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * Dragy channels beyond the shared [GnssChannels]. Ids 620 to 639 are reserved for the Dragy
 * (600 to 699 is shared with the VBOX Sport, which uses 600 to 619).
 */
object DragyChannels {
    const val SOURCE = "dragy"

    /** NAV-PVT fixType: 0 none, 1 dead reckoning, 2 2D, 3 3D, 4 GNSS + dead reckoning, 5 time only. */
    val FIX_TYPE = ChannelDef(620, "dragy_fix_type", "", SOURCE, 0)
    val H_ACC = ChannelDef(621, "dragy_hacc", "m", SOURCE, 2)
    val SPEED_ACC = ChannelDef(622, "dragy_speed_acc", "km/h", SOURCE, 2)
    val PDOP = ChannelDef(623, "dragy_pdop", "", SOURCE, 2)

    val ALL = listOf(FIX_TYPE, H_ACC, SPEED_ACC, PDOP)
}

/** Event kinds the Dragy source writes into the session. */
object DragyEvents {
    /** Text is "address=<address> name=<name>", written once at start. */
    const val DEVICE = "dragy_device"

    /** Text is the device address. */
    const val CONNECTED = "dragy_connected"

    /** Text is the reason. */
    const val DISCONNECTED = "dragy_disconnected"

    /** The same kinds as the other receivers. */
    const val FIX_ACQUIRED = "gnss_fix"
    const val FIX_LOST = "gnss_fix_lost"
}

private const val KmhPerMps = 3.6

/**
 * Turns the Dragy's `FD02` notifications into sample sets: UBX frames are reassembled with the
 * RaceBox's [UbxFrameReader] (same framing and Fletcher checksum), and each UBX-NAV-PVT ([NavPvt])
 * becomes one set stamped with the recorder clock on arrival. Other UBX messages are ignored.
 * Position, speed, heading and altitude (above mean sea level) only with a fix
 * ([NavPvt.hasFix]), so the VBO export never gets rows at 0°, 0°. Synchronized: notifications may
 * arrive on more than one binder thread. Unverified on a real Dragy (see [DragyGatt]).
 */
class DragyDecoder(private val sink: SampleSink) {
    private val reader = UbxFrameReader()
    private var hadFix: Boolean? = null

    /** NAV-PVT messages decoded since the decoder was created. */
    @Volatile
    var messages = 0L
        private set

    val checksumErrors: Long get() = synchronized(this) { reader.checksumErrors }

    fun defineChannels() {
        GnssChannels.ALL.forEach(sink::defineChannel)
        DragyChannels.ALL.forEach(sink::defineChannel)
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        reader.feed(bytes) { frame ->
            if (frame.messageClass != NavPvtClass || frame.messageId != NavPvtId) return@feed
            val pvt = NavPvt.parse(frame.payload) ?: return@feed
            messages++
            if (pvt.hasFix != hadFix) {
                if (hadFix != null || pvt.hasFix) {
                    val kind = if (pvt.hasFix) DragyEvents.FIX_ACQUIRED else DragyEvents.FIX_LOST
                    sink.event(kind, "sats=${pvt.satellites}")
                }
                hadFix = pvt.hasFix
            }
            sink.submit(SampleSet(sink.nowMicros(), values(pvt)))
        }
    }

    /** Drops a partial packet left from before a reconnect. */
    @Synchronized
    fun reset() {
        reader.reset()
    }

    private fun values(p: NavPvt): List<ChannelValue> = buildList {
        p.utcMillis?.let { add(ChannelValue(GnssChannels.UTC_MS.id, it.toDouble())) }
        add(ChannelValue(GnssChannels.SATS.id, p.satellites.toDouble()))
        add(ChannelValue(DragyChannels.FIX_TYPE.id, p.fixType.toDouble()))
        add(ChannelValue(DragyChannels.PDOP.id, p.pdop))
        if (!p.hasFix) return@buildList
        add(ChannelValue(GnssChannels.LAT.id, p.latDeg))
        add(ChannelValue(GnssChannels.LON.id, p.lonDeg))
        add(ChannelValue(GnssChannels.SPEED_KMH.id, p.speedKmh))
        // Heading of motion, as for the RaceBox: headVehValid is only set in sensor-fusion mode.
        add(ChannelValue(GnssChannels.HEADING.id, p.headingDeg))
        add(ChannelValue(GnssChannels.ALT_M.id, p.mslAltM))
        add(ChannelValue(DragyChannels.H_ACC.id, p.horizontalAccM))
        add(ChannelValue(DragyChannels.SPEED_ACC.id, p.speedAccMps * KmhPerMps))
    }
}
