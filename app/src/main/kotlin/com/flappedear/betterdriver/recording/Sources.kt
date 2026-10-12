package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.flappedear.betterdriver.core.ble.ImuCalibrationStore
import com.flappedear.betterdriver.core.ble.RaceBoxSource
import com.flappedear.betterdriver.core.ble.diy.DiyBleSource
import com.flappedear.betterdriver.core.ble.dragy.DragySource
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.lns.LnsSource
import com.flappedear.betterdriver.core.ble.nmea.NmeaSource
import com.flappedear.betterdriver.core.ble.nmea.TcpNmeaSource
import com.flappedear.betterdriver.core.ble.vbox.VboxSportSource
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.TcpAddress
import java.io.File
import java.io.IOException
import javax.net.SocketFactory

/** Where sessions are stored. Both processes use the same app-private directories. */
internal fun sessionsDir(context: Context) = File(context.filesDir, "sessions")

/** Saved tracks (FBBD-54). The editor writes them; the recorder reads them at each session start. */
internal fun tracksDir(context: Context) = File(context.filesDir, "tracks")

/** RaceBox motion-sensor calibrations (FBBD-89). The diagnostics screen writes them; the recorder reads them. */
internal fun imuCalibrations(context: Context) = ImuCalibrationStore(File(context.filesDir, "imu-calibration"))

/** A newer circuit list, if one was fetched (FBBD-79). */
internal fun circuitsDir(context: Context) = File(context.filesDir, "circuits")

/** Which kind of receiver a [GnssRef] is, and so which source records it. */
enum class GnssType {
    /** A RaceBox Mini, Mini S or Micro (FBBD-43). */
    RACEBOX,

    /** A Bluetooth NMEA receiver paired in Android settings (FBBD-108). */
    NMEA,

    /** A Bluetooth LE receiver with the Location and Navigation Service (FBBD-114). */
    LNS,

    /** A RaceChrono DIY Bluetooth LE GPS device, service 0x1FF8 (FBBD-118). */
    DIY,

    /** A receiver on Wi-Fi sending NMEA or $RC2/$RC3 over TCP; its address is "host:port" (FBBD-115). */
    TCP,

    /** A RACELOGIC VBOX Sport paired in Android settings, RACELOGIC Serial Stream over Bluetooth Classic (FBBD-116). */
    VBOX,

    /** A Dragy Pro / DRG70 over BLE, community-documented protocol, unverified on hardware (FBBD-117). */
    DRAGY,

    /** The made-up positions of Demo GNSS, which Arek adds as equipment on purpose (FBBD-83). */
    DEMO,
}

/**
 * The session's GNSS receiver: its Bluetooth address and advertised name, and its [type]. A
 * RaceBox is told apart from others by the advertised name, so the name is the device's, not Arek's.
 */
data class GnssRef(
    val address: String,
    val name: String,
    val type: GnssType = GnssType.RACEBOX,
) {
    val demo: Boolean get() = type == GnssType.DEMO

    companion object {
        val Demo = GnssRef("", "Demo GNSS", GnssType.DEMO)
    }
}

/** A sensor recorded alongside the receiver: a heart-rate monitor by address and name (FBBD-119). */
data class SensorRef(val address: String, val name: String) {
    /** One line for an intent extra or a preference: address, a tab, then the name. */
    fun encode(): String = "$address\t$name"

    companion object {
        fun decode(line: String): SensorRef? =
            line.split('\t', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { SensorRef(it[0], it[1]) }
    }
}

/** Which source records each receiver model. */
private val GnssTypes = mapOf(
    EquipmentModel.DEMO_GNSS to GnssType.DEMO,
    EquipmentModel.RACEBOX to GnssType.RACEBOX,
    EquipmentModel.NMEA_GNSS to GnssType.NMEA,
    EquipmentModel.LNS_GNSS to GnssType.LNS,
    EquipmentModel.DIY_GNSS to GnssType.DIY,
    EquipmentModel.TCP_GNSS to GnssType.TCP,
    EquipmentModel.VBOX_SPORT to GnssType.VBOX,
    EquipmentModel.DRAGY to GnssType.DRAGY,
)

/** The receiver an equipment item is, or null when it is not a receiver. */
fun gnssRefOf(e: Equipment): GnssRef? {
    val type = GnssTypes[e.model]
    // The recorder tells a RaceBox Micro from a Mini by the advertised name, not Arek's name for it.
    return when (type) {
        null -> null
        GnssType.DEMO -> GnssRef.Demo
        else -> GnssRef(e.address.orEmpty(), e.advertisedName ?: e.name, type)
    }
}

/**
 * Data sources for a new recording: the chosen receiver, each kind of which reconnects on its own
 * if the link drops, or Demo GNSS, plus the first listed heart rate monitor. There is no fallback: without a
 * receiver nothing is recorded, never simulated positions (FBBD-83).
 */
internal object Sources {
    fun create(context: Context, gnss: GnssRef, sensors: List<SensorRef> = emptyList()): List<DataSource> {
        val app = context.applicationContext
        val receiver = when (gnss.type) {
            GnssType.DEMO -> FakeGnssSource()
            GnssType.NMEA -> NmeaSource(app, gnss.address, gnss.name)
            GnssType.LNS -> LnsSource(app, gnss.address, gnss.name)
            GnssType.DIY -> DiyBleSource(app, gnss.address, gnss.name)
            GnssType.TCP -> tcpSource(app, gnss)
            GnssType.VBOX -> VboxSportSource(app, gnss.address, gnss.name)
            GnssType.DRAGY -> DragySource(app, gnss.address, gnss.name)
            GnssType.RACEBOX -> RaceBoxSource(app, gnss.address, gnss.name, imuCalibrations(context).load(gnss.address))
        }
        // Heart rate has fixed channel ids, so two monitors would merge into one stream: the first records.
        return listOf(receiver) + sensors.take(1).map { HeartRateSource(app, it.address, it.name) }
    }
}

/** A Wi-Fi receiver's source; its socket goes over Wi-Fi even when Wi-Fi has no internet. */
private fun tcpSource(context: Context, gnss: GnssRef): DataSource {
    val at = TcpAddress.parse(gnss.address) ?: throw IOException("Not a host and port: ${gnss.address}")
    return TcpNmeaSource(at.host, at.port, gnss.name, wifiSocketFactory(context))
}

/**
 * The Wi-Fi network's socket factory. A receiver that runs its own access point has no internet,
 * and Android then sends other sockets over mobile data. Falls back to the default with no Wi-Fi.
 */
@Suppress("DEPRECATION") // allNetworks: the callback API would need a listener for one lookup.
private fun wifiSocketFactory(context: Context): SocketFactory {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val wifi = cm?.allNetworks?.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    return wifi?.socketFactory ?: SocketFactory.getDefault()
}
