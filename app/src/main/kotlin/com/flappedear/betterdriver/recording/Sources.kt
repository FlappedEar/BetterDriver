package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.core.ble.ImuCalibrationStore
import com.flappedear.betterdriver.core.ble.RaceBoxSource
import com.flappedear.betterdriver.core.ble.hr.HeartRateSource
import com.flappedear.betterdriver.core.ble.lns.LnsSource
import com.flappedear.betterdriver.core.ble.nmea.NmeaSource
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import com.flappedear.betterdriver.setup.Equipment
import com.flappedear.betterdriver.setup.EquipmentModel
import java.io.File

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

/** The receiver an equipment item is, or null when it is not a receiver. */
fun gnssRefOf(e: Equipment): GnssRef? {
    val type = when (e.model) {
        EquipmentModel.DEMO_GNSS -> GnssType.DEMO
        EquipmentModel.RACEBOX -> GnssType.RACEBOX
        EquipmentModel.NMEA_GNSS -> GnssType.NMEA
        EquipmentModel.LNS_GNSS -> GnssType.LNS
        else -> null
    }
    // The recorder tells a RaceBox Micro from a Mini by the advertised name, not Arek's name for it.
    return when (type) {
        null -> null
        GnssType.DEMO -> GnssRef.Demo
        else -> GnssRef(e.address.orEmpty(), e.advertisedName ?: e.name, type)
    }
}

/**
 * Data sources for a new recording: the chosen receiver, each kind of which reconnects on its own
 * if the link drops, or Demo GNSS, plus every listed sensor. There is no fallback: without a
 * receiver nothing is recorded, never simulated positions (FBBD-83).
 */
internal object Sources {
    fun create(context: Context, gnss: GnssRef, sensors: List<SensorRef> = emptyList()): List<DataSource> {
        val app = context.applicationContext
        val receiver = when (gnss.type) {
            GnssType.DEMO -> FakeGnssSource()
            GnssType.NMEA -> NmeaSource(app, gnss.address, gnss.name)
            GnssType.LNS -> LnsSource(app, gnss.address, gnss.name)
            GnssType.RACEBOX -> RaceBoxSource(app, gnss.address, gnss.name, imuCalibrations(context).load(gnss.address))
        }
        return listOf(receiver) + sensors.map { HeartRateSource(app, it.address, it.name) }
    }
}
