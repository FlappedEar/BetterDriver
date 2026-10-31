package com.flappedear.betterdriver.core.ble

import android.content.Context
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import com.flappedear.betterdriver.recorder.log.DebugLog
import kotlinx.coroutines.flow.StateFlow

/**
 * The RaceBox as a recorder [DataSource]. The recorder service attaches it to a recording; it
 * connects to the paired RaceBox at [address], keeps reconnecting until stopped, and pushes one
 * sample set per RaceBox Data Message. Connects and drops are logged as session events.
 *
 * [deviceName] is the advertised name saved at pairing; it tells a Micro (input voltage) from a Mini
 * (battery level). [calibration] is the motion-sensor calibration taken on the diagnostics screen
 * for this RaceBox, if any (FBBD-89).
 */
class RaceBoxSource(
    private val context: Context,
    private val address: String,
    deviceName: String,
    private val calibration: ImuCalibration? = null,
) : DataSource {
    override val name = "racebox"
    private val isMicro = deviceName.startsWith(RaceBoxGatt.MICRO_PREFIX)

    @Volatile
    private var link: RaceBoxLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        val session = RaceBoxSession(sink, isMicro, calibration, log = { DebugLog.i("RaceBox", it) }) { reason ->
            link?.requestFastLink(reason)
        }
        session.start()
        link = RaceBoxLink(context, address, onData = session::feed, onState = { s -> session.linkChanged(address, s) })
            .also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
