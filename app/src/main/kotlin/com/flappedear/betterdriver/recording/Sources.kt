package com.flappedear.betterdriver.recording

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.flappedear.betterdriver.core.ble.ImuCalibrationStore
import com.flappedear.betterdriver.core.ble.RaceBoxSource
import com.flappedear.betterdriver.core.ble.diy.DiyBleSource
import com.flappedear.betterdriver.core.ble.dragy.DragySource
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.lns.LnsSource
import com.flappedear.betterdriver.core.ble.nmea.NmeaSource
import com.flappedear.betterdriver.core.ble.nmea.TcpNetwork
import com.flappedear.betterdriver.core.ble.nmea.TcpNmeaSource
import com.flappedear.betterdriver.core.ble.obd.ObdSource
import com.flappedear.betterdriver.core.ble.vbox.VboxSportSource
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.Recording
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import com.flappedear.betterdriver.setup.TcpAddress
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.SessionTiming
import com.flappedear.betterdriver.timing.live.lapLineText
import com.flappedear.betterdriver.timing.track.TrackEvent
import com.flappedear.betterdriver.timing.track.TrackStore
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

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

    /** A made-up car lapping the demo circuit, whose track is saved for it (FBBD-233). */
    DEMO_TRACK,

    /** The phone's own GPS, about one position a second, when there is no receiver (FBBD-275). */
    PHONE,
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
    val demo: Boolean get() = type == GnssType.DEMO || type == GnssType.DEMO_TRACK

    companion object {
        val Demo = GnssRef("", "Demo GNSS", GnssType.DEMO)
        val DemoTrack = GnssRef("", "Demo track", GnssType.DEMO_TRACK)
        val Phone = GnssRef("", "Phone GPS", GnssType.PHONE)

        /** The demo receiver of [type]: Demo GNSS, or the demo track. */
        fun demoOf(type: GnssType): GnssRef = if (type == GnssType.DEMO_TRACK) DemoTrack else Demo
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
    EquipmentModel.DEMO_TRACK to GnssType.DEMO_TRACK,
    EquipmentModel.RACEBOX to GnssType.RACEBOX,
    EquipmentModel.NMEA_GNSS to GnssType.NMEA,
    EquipmentModel.LNS_GNSS to GnssType.LNS,
    EquipmentModel.DIY_GNSS to GnssType.DIY,
    EquipmentModel.TCP_GNSS to GnssType.TCP,
    EquipmentModel.VBOX_SPORT to GnssType.VBOX,
    EquipmentModel.DRAGY to GnssType.DRAGY,
    EquipmentModel.PHONE_GPS to GnssType.PHONE,
)

/** The receiver an equipment item is, or null when it is not a receiver. */
fun gnssRefOf(e: Equipment): GnssRef? {
    val type = GnssTypes[e.model]
    // The recorder tells a RaceBox Micro from a Mini by the advertised name, not Arek's name for it.
    return when (type) {
        null -> null
        GnssType.DEMO, GnssType.DEMO_TRACK -> GnssRef.demoOf(type)
        // One name in the log and the session whatever Arek calls it (DriveChecks reads "Phone_GPS").
        GnssType.PHONE -> GnssRef.Phone
        else -> GnssRef(e.address.orEmpty(), e.advertisedName ?: e.name, type)
    }
}

/**
 * Data sources for a new recording: the chosen receiver, each kind of which reconnects on its own
 * if the link drops, or Demo GNSS, plus the phone's accelerometer and the first listed heart rate
 * monitor, and the listed OBD-II adapters (FBBD-15, FBBD-121). Never simulated positions in place
 * of a receiver (FBBD-83); the phone's own GPS is a real one, recorded as such (FBBD-275).
 */
internal object Sources {
    fun create(context: Context, gnss: GnssRef, sensors: List<SensorRef> = emptyList()): List<DataSource> {
        val app = context.applicationContext
        val receiver = when (gnss.type) {
            // Debug builds let the emulator tests switch it off (FBBD-43, FBBD-64).
            GnssType.DEMO -> FakeGnssSource(receiverOn = DemoGnssHook.receiverOn(app))
            GnssType.DEMO_TRACK -> {
                val script = DemoReplay.script(app)
                FakeGnssSource(script, receiverOn = DemoGnssHook.receiverOn(app))
            }
            GnssType.NMEA -> NmeaSource(app, gnss.address, gnss.name)
            GnssType.LNS -> LnsSource(app, gnss.address, gnss.name)
            GnssType.DIY -> DiyBleSource(app, gnss.address, gnss.name)
            GnssType.TCP -> tcpSource(app, gnss)
            GnssType.VBOX -> VboxSportSource(app, gnss.address, gnss.name)
            GnssType.DRAGY -> DragySource(app, gnss.address, gnss.name)
            GnssType.PHONE -> PhoneGnssSource(app)
            GnssType.RACEBOX -> RaceBoxSource(app, gnss.address, gnss.name, imuCalibrations(context).load(gnss.address))
        }
        // Heart rate has fixed channel ids, so two monitors would merge into one stream: the first records.
        // The phone's accelerometer always records: G from a real accelerometer when the receiver's does
        // not match the GPS (FBBD-218). Not with Demo GNSS, whose made-up drive it would not match.
        val phone = if (gnss.demo) emptyList() else listOf(PhoneImuSource(app))
        // OBD-II from the adapters listed when the session started (UI-105 C); none listed, none read.
        val carData = if (gnss.demo) emptyList() else sessionCarData(app)
        val obd = if (carData.isEmpty()) {
            emptyList()
        } else {
            // Only what is used: the pedals, rpm, temperatures and the tiles' channels (FBBD-283), or the
            // PIDs switched on for the car, and the car's own parameters (FBBD-344).
            listOf(
                ObdSource(
                    { obdTargets(app, carData) },
                    profile = sessionCarProfile(app),
                    watch = { obdWatch(app, it) },
                    pollSet = sessionPollSet(app),
                ),
            )
        }
        return listOf(receiver) + phone + obd + sensors.take(1).map { HeartRateSource(app, it.address, it.name) }
    }
}

/** A Wi-Fi receiver's source; its socket goes over Wi-Fi even when Wi-Fi has no internet. */
private fun tcpSource(context: Context, gnss: GnssRef): DataSource {
    val at = TcpAddress.parse(gnss.address) ?: throw IOException("Not a host and port: ${gnss.address}")
    return TcpNmeaSource(at.host, at.port, gnss.name, network = { wifiNetwork(context) })
}

/**
 * The Wi-Fi network, looked up for each connect: after Wi-Fi drops and comes back it can be a new
 * Network, and the old one's sockets no longer work. A receiver that runs its own access point has
 * no internet, and Android then sends other sockets and name lookups over mobile data. Falls back to
 * the default with no Wi-Fi.
 */
private fun wifiNetwork(context: Context): TcpNetwork = wifi(context)?.let(::onNetwork) ?: TcpNetwork.Default

@Suppress("DEPRECATION") // allNetworks: the callback API would need a listener for one lookup.
internal fun wifi(context: Context): Network? {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return cm.allNetworks.firstOrNull {
        cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
}

internal fun onNetwork(network: Network): TcpNetwork = object : TcpNetwork {
    override fun createSocket(): Socket = network.socketFactory.createSocket()

    override fun resolve(host: String): InetAddress = network.getByName(host)
}

/**
 * Picks the track at the first fix and times laps there (FBBD-55, FBBD-12). It runs on the
 * listener thread, so a timing failure is logged in the session and never stops it. Each lap is
 * logged at its crossing with the predicted lap time shown before its line (FBBD-183), also a lap
 * timed after the fact once a provisional line is placed; with no line here, a
 * provisional one is placed once the car has driven a loop, and logged (FBBD-335).
 */
internal fun sessionTiming(tracks: File, recording: Recording, circuits: CircuitList) =
    SessionTiming({ TrackStore(tracks).all() }, circuits, onLap = { lap, predicted ->
        recording.eventAt(lap.endMicros, EventKinds.LAP_LINE, lapLineText(lap, predicted))
    }, onProvisional = { made ->
        recording.event(TrackEvent.PROVISIONAL_KIND, TrackEvent.provisionalText(made))
    }) { found -> recording.event(TrackEvent.KIND, TrackEvent.text(found)) }
