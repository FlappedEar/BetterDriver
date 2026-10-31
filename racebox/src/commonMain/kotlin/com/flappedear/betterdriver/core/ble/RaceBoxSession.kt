package com.flappedear.betterdriver.core.ble

import com.flappedear.betterdriver.recorder.SampleSink

/**
 * What a RaceBox link does to a recording, the same on Android (RaceBoxSource over its GATT link)
 * and iOS (CoreBluetooth): define the channels, note the motion-sensor calibration, decode each
 * notification, and log connects and drops as session events. The platform link reports
 * [streaming] when data flows and [reconnecting] when the link drops.
 */
class RaceBoxSession(
    private val sink: SampleSink,
    isMicro: Boolean,
    private val calibration: ImuCalibration?,
    log: (String) -> Unit,
    onSlowLink: (String) -> Unit,
) {
    val decoder = RaceBoxDecoder(sink, isMicro, calibration, log, onSlowLink)

    /** Whether data flowed since the last drop; a drop before any data is not a disconnect. */
    var wasStreaming = false
        private set

    fun start() {
        decoder.defineChannels()
        sink.event(RaceBoxEvents.IMU_CALIBRATION, calibration?.encode() ?: "none")
    }

    fun feed(bytes: ByteArray) = decoder.feed(bytes)

    /** Data flows from the RaceBox at [address]. */
    fun streaming(address: String) {
        if (!wasStreaming) sink.event(RaceBoxEvents.CONNECTED, address)
        wasStreaming = true
    }

    /** The link dropped for [reason]; a partial packet from before is thrown away. */
    fun reconnecting(reason: String) {
        if (wasStreaming) sink.event(RaceBoxEvents.DISCONNECTED, reason)
        decoder.reset()
        wasStreaming = false
    }

    /**
     * The RaceBox link at [address] is now [s], on either platform (FBBD-358): logged as every link
     * is, then [streaming] or [reconnecting].
     */
    fun linkChanged(address: String, s: LinkState) {
        logLink(address, s, wasStreaming)
        when (s) {
            is LinkState.Streaming -> streaming(address)
            is LinkState.Reconnecting -> reconnecting(s.reason)
            else -> Unit
        }
    }
}
