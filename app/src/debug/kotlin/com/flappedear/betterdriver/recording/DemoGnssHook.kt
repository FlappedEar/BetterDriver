package com.flappedear.betterdriver.recording

import android.content.Context
import java.io.File

/**
 * The emulator tests' switch for Demo GNSS (FBBD-43, FBBD-64): while the file [FileName] is in the
 * app's files directory, Demo GNSS is switched off, as a RaceBox out of range, and sends no fixes.
 * The test writes and deletes it from the UI process; the recorder process reads it before each fix.
 *
 * Debug builds only: release builds compile the twin in src/release, where Demo GNSS is always on.
 */
object DemoGnssHook {
    const val FileName = "demo-gnss-off"

    /** Whether Demo GNSS is on now, read on the source's own thread. */
    fun receiverOn(context: Context): () -> Boolean {
        val off = File(context.filesDir, FileName)
        return { !off.exists() }
    }
}
