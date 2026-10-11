package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.core.ble.RaceBoxSource
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import java.io.File

/** Where sessions are stored. Both processes use the same app-private directories. */
internal fun sessionsDir(context: Context) = File(context.filesDir, "sessions")

/** Saved tracks (FBBD-54). The editor writes them; the recorder reads them at each session start. */
internal fun tracksDir(context: Context) = File(context.filesDir, "tracks")

/** A newer circuit list, if one was fetched (FBBD-79). */
internal fun circuitsDir(context: Context) = File(context.filesDir, "circuits")

/** A paired RaceBox: its Bluetooth address and advertised name. */
data class RaceBoxRef(val address: String, val name: String)

/**
 * Data sources for a new recording: the paired RaceBox (FBBD-43), which reconnects on its own if
 * the link drops. With no RaceBox paired it is the fake GNSS ring, so the whole recording path can
 * still be used without hardware.
 */
internal object Sources {
    fun create(context: Context, raceBox: RaceBoxRef?): List<DataSource> = listOf(
        raceBox?.let { RaceBoxSource(context.applicationContext, it.address, it.name) } ?: FakeGnssSource(),
    )
}
