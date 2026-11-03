package com.flappedear.betterdriver.core.ble.diy

import android.content.Context
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.GattLink
import com.flappedear.betterdriver.core.ble.GnssReceiverSession
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.advertisesService
import com.flappedear.betterdriver.core.ble.scanBleDevices
import com.flappedear.betterdriver.core.ble.tpms.TpmsIds
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * RaceChrono DIY devices in range that advertise service 0x1FF8, strongest first (FBBD-118). The
 * TPMS bridge uses the same service and is listed as a bridge instead ([TpmsIds.isBridge], FBBD-224).
 */
fun scanDiyDevices(context: Context): Flow<List<FoundBleDevice>> =
    scanBleDevices(context) { name, uuids -> advertisesService(uuids, DiyGatt.SERVICE) && !TpmsIds.isBridge(name) }

/**
 * A RaceChrono DIY BLE GPS device (service 0x1FF8, https://github.com/aollin/racechrono-ble-diy-device)
 * as a recorder [DataSource] (FBBD-118). It connects over BLE to the device at [address] with a
 * [GattLink], subscribes to GPS main (and GPS time when it notifies), reads GPS time when the
 * decoder needs it, keeps reconnecting until stopped, and pushes one sample set per GPS main
 * value. The CAN-Bus characteristics of the same service are left alone until CAN logging
 * (Phase 5). Connects and drops are logged as session events.
 *
 * [deviceName] is the advertised name saved when it was added, logged so a session says which
 * device it came from.
 */
class DiyBleSource(
    private val context: Context,
    private val address: String,
    private val deviceName: String,
) : DataSource {
    override val name = "racechrono_diy"

    @Volatile
    private var link: GattLink? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    override fun start(sink: SampleSink) {
        // The same session as the iPhone's (FBBD-374).
        val session = GnssReceiverSession.diy(sink, address, deviceName, requestTime = { link?.read(DiyGatt.GPS_TIME) })
        session.start()
        val onData = { uuid: UUID, bytes: ByteArray ->
            when (uuid) {
                DiyGatt.GPS_MAIN -> session.feed(bytes)
                DiyGatt.GPS_TIME -> session.feedTime(bytes)
                else -> Unit
            }
        }
        link = GattLink(context, address, DiyGatt.PROFILE, onData = onData, onState = session::linkChanged)
            .also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
