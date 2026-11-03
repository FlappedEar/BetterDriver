package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.core.ble.diy.DiyEvents
import com.flappedear.betterdriver.core.ble.diy.DiyGpsDecoder
import com.flappedear.betterdriver.core.ble.lns.LnsDecoder
import com.flappedear.betterdriver.core.ble.lns.LnsEvents
import com.flappedear.betterdriver.core.ble.nmea.NmeaDecoder
import com.flappedear.betterdriver.core.ble.nmea.NmeaEvents
import com.flappedear.betterdriver.recorder.SampleSink

/**
 * Bluetooth SIG numbers of the Location and Navigation Service (FBBD-114), as 16-bit numbers in
 * text, as CoreBluetooth takes them; Android's LnsGatt makes UUIDs of them.
 */
object LnsIds {
    /** Location and Navigation Service. */
    const val SERVICE = "1819"

    /** Location and Speed: notified, one value per fix. */
    const val LOCATION_AND_SPEED = "2A67"
}

/**
 * What a GNSS receiver's link does to a recording, the same on Android and iOS (FBBD-374): define
 * the receiver's channels, log the device, decode what it sends, and log connects and drops as
 * session events. The platform link (Android: SPP, TCP or GATT; iOS: TCP or CoreBluetooth) reports
 * its state to [linkChanged] and passes the data to [feed] (and, for a RaceChrono DIY device, GPS
 * time to [feedTime]).
 *
 * [address] names the receiver in the session: "host:port" for Wi-Fi, Android's Bluetooth address
 * or the iPhone's identifier. [deviceName] is the name saved when it was added. Made by [nmea],
 * [lns] or [diy].
 */
@Suppress("LongParameterList") // What each kind of receiver differs in, set by its factory below.
class GnssReceiverSession private constructor(
    private val sink: SampleSink,
    private val address: String,
    private val deviceName: String,
    private val deviceEvent: String,
    private val define: () -> Unit,
    private val main: (ByteArray) -> Unit,
    private val time: (ByteArray) -> Unit,
    connected: String,
    disconnected: String,
    reset: () -> Unit,
) {
    private val onState = linkEvents(sink, address, connected, disconnected, reset)

    /** Defines the channels and logs the device; once, before the link starts. */
    fun start() {
        define()
        sink.event(deviceEvent, "address=$address name=$deviceName")
    }

    /**
     * Bytes from the receiver: a chunk of an NMEA stream, one Location and Speed value, or one
     * RaceChrono DIY GPS main value.
     */
    fun feed(bytes: ByteArray) = main(bytes)

    /** A RaceChrono DIY GPS time value (notified or read); other receivers ignore it. */
    fun feedTime(bytes: ByteArray) = time(bytes)

    /** The link's new state [s]: logged, and connects and drops as events; a reconnect resets the decoder. */
    fun linkChanged(s: LinkState) = onState(s)

    companion object {
        /** An NMEA 0183 receiver, or a stream with RaceChrono DIY $RC2/$RC3 lines (FBBD-108, FBBD-115). */
        fun nmea(sink: SampleSink, address: String, deviceName: String): GnssReceiverSession {
            val d = NmeaDecoder(sink)
            return GnssReceiverSession(
                sink, address, deviceName, NmeaEvents.DEVICE, d::defineChannels, d::feed, {},
                NmeaEvents.CONNECTED, NmeaEvents.DISCONNECTED, d::reset,
            )
        }

        /** A Bluetooth LE receiver with the Location and Navigation Service (FBBD-114). */
        fun lns(sink: SampleSink, address: String, deviceName: String): GnssReceiverSession {
            val d = LnsDecoder(sink)
            return GnssReceiverSession(
                sink, address, deviceName, LnsEvents.DEVICE, d::defineChannels, d::feed, {},
                LnsEvents.CONNECTED, LnsEvents.DISCONNECTED, d::reset,
            )
        }

        /**
         * A RaceChrono DIY Bluetooth LE GPS device (FBBD-118). [requestTime] asks the link to read
         * GPS time, which the decoder needs and a device need not notify.
         */
        fun diy(sink: SampleSink, address: String, deviceName: String, requestTime: () -> Unit): GnssReceiverSession {
            val d = DiyGpsDecoder(sink, requestTime)
            return GnssReceiverSession(
                sink, address, deviceName, DiyEvents.DEVICE, d::defineChannels, d::feedMain, d::feedTime,
                DiyEvents.CONNECTED, DiyEvents.DISCONNECTED, d::reset,
            )
        }
    }
}
