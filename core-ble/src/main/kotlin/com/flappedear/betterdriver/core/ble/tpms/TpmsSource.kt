package com.flappedear.betterdriver.core.ble.tpms

import android.content.Context
import com.flappedear.betterdriver.core.ble.FoundBleDevice
import com.flappedear.betterdriver.core.ble.GattNotifyLink
import com.flappedear.betterdriver.core.ble.GattNotifySpec
import com.flappedear.betterdriver.core.ble.GattWrite
import com.flappedear.betterdriver.core.ble.LinkState
import com.flappedear.betterdriver.core.ble.NotifyLink
import com.flappedear.betterdriver.core.ble.advertisesService
import com.flappedear.betterdriver.core.ble.scanBleDevices
import com.flappedear.betterdriver.core.ble.sigUuid
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.SampleSink
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The TPMS bridge's RaceChrono DIY service and characteristics as UUIDs (FBBD-224). */
object TpmsGatt {
    val SERVICE: UUID = sigUuid(TpmsIds.SERVICE.toInt(Hex))
    val VALUES: UUID = sigUuid(TpmsIds.VALUES.toInt(Hex))
    val FILTER: UUID = sigUuid(TpmsIds.FILTER.toInt(Hex))
}

private const val Hex = 16

/**
 * The bridge sends each heard wheel's values every second, and nothing before it hears its first
 * sensor (a car parked a while: the sensors sleep). So the setup waits 2 min for a first value,
 * and 30 s without any after that means the link is gone.
 */
private val TpmsSpec = GattNotifySpec(
    TpmsGatt.SERVICE,
    TpmsGatt.VALUES,
    "tpms-link",
    silenceTimeoutMs = 30_000L,
    setupTimeoutMs = 120_000L,
    afterSubscribe = GattWrite(TpmsGatt.FILTER, TpmsIds.AllowAllEverySecond),
)

/** TPMS bridges in range: RaceChrono DIY devices (service 0x1FF8) named "RC TPMS ...", strongest first. */
fun scanTpmsBridges(context: Context): Flow<List<FoundBleDevice>> =
    scanBleDevices(context) { name, uuids -> advertisesService(uuids, TpmsGatt.SERVICE) && TpmsIds.isBridge(name) }

/**
 * The FlappedEar TPMS bridge (RCTPMS) as a recorder [DataSource] (FBBD-224): connects to it at
 * [address], subscribes to its values, writes the filter that lets them through, keeps reconnecting
 * until stopped, and records the four tyres' pressures and temperatures ([TpmsChannels]). What it
 * does to the recording is [TpmsSession], shared with the iOS app. A lost bridge never stops the
 * session or the GPS.
 *
 * [connect] makes the link from its two callbacks; tests pass a simulated bridge.
 */
class TpmsSource internal constructor(
    private val address: String,
    private val deviceName: String,
    private val connect: (onData: (ByteArray) -> Unit, onState: (LinkState) -> Unit) -> NotifyLink,
) : DataSource {
    constructor(context: Context, address: String, deviceName: String) : this(
        address,
        deviceName,
        { onData, onState -> GattNotifyLink(context, address, TpmsSpec, onData, onState) },
    )

    override val name = TpmsChannels.SOURCE

    @Volatile
    private var link: NotifyLink? = null

    @Volatile
    private var session: TpmsSession? = null

    /** The live link state while started, for status displays. Null when stopped. */
    val state: StateFlow<LinkState>? get() = link?.state

    /** The four tyres' latest values while started, front left to rear right. */
    fun readings(): List<TyreReading>? = if (link == null) null else session?.readings()

    override fun start(sink: SampleSink) {
        val session = TpmsSession(sink, address, deviceName)
        this.session = session
        session.start()
        link = connect(session::feed, session::linkChanged).also { it.start() }
    }

    override fun stop() {
        link?.stop()
        link = null
    }
}
