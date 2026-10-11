package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.core.ble.ImuCalibrationStore
import com.flappedear.betterdriver.core.ble.RaceBoxSource
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import java.io.File

/** Where sessions are stored. Both processes use the same app-private directories. */
internal fun sessionsDir(context: Context) = File(context.filesDir, "sessions")

/** Saved tracks (FBBD-54). The editor writes them; the recorder reads them at each session start. */
internal fun tracksDir(context: Context) = File(context.filesDir, "tracks")

/** RaceBox motion-sensor calibrations (FBBD-89). The diagnostics screen writes them; the recorder reads them. */
internal fun imuCalibrations(context: Context) = ImuCalibrationStore(File(context.filesDir, "imu-calibration"))

/** A newer circuit list, if one was fetched (FBBD-79). */
internal fun circuitsDir(context: Context) = File(context.filesDir, "circuits")

/**
 * The session's GNSS receiver: a paired RaceBox by Bluetooth address and advertised name, or, with
 * [demo], the made-up positions of Demo GNSS, which Arek adds as equipment on purpose (FBBD-83).
 */
data class GnssRef(val address: String, val name: String, val demo: Boolean = false) {
    companion object {
        val Demo = GnssRef("", "Demo GNSS", demo = true)
    }
}

/**
 * Data sources for a new recording: the chosen RaceBox (FBBD-43), which reconnects on its own if
 * the link drops, or Demo GNSS. There is no fallback: without a receiver nothing is recorded,
 * never simulated positions (FBBD-83).
 */
internal object Sources {
    fun create(context: Context, gnss: GnssRef): List<DataSource> = listOf(
        if (gnss.demo) {
            FakeGnssSource()
        } else {
            val calibration = imuCalibrations(context).load(gnss.address)
            RaceBoxSource(context.applicationContext, gnss.address, gnss.name, calibration)
        },
    )
}
