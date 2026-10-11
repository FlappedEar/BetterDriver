package com.flappedear.betterdriver.recording

import android.content.Context
import com.flappedear.betterdriver.recorder.DataSource
import com.flappedear.betterdriver.recorder.fake.FakeGnssSource
import java.io.File

/** Where sessions are stored. Both processes use the same app-private directories. */
internal fun sessionsDir(context: Context) = File(context.filesDir, "sessions")

/** Saved tracks (FBBD-54). The editor writes them; the recorder reads them at each session start. */
internal fun tracksDir(context: Context) = File(context.filesDir, "tracks")

/** A newer circuit list, if one was fetched (FBBD-79). */
internal fun circuitsDir(context: Context) = File(context.filesDir, "circuits")

/**
 * Data sources for a new recording. Until the RaceBox Micro link lands, this is the fake GNSS
 * ring, so the whole recording path can be used without hardware.
 */
internal object Sources {
    fun create(): List<DataSource> = listOf(FakeGnssSource())
}
