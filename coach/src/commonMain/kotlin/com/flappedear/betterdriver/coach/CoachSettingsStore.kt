package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.io.CoreFiles
import com.flappedear.betterdriver.io.IOException

/**
 * Coach settings in one small file (FBBD-98), in the java.util.Properties format. The app writes it;
 * the recorder and coach processes read it at each session start, so a change applies from the next
 * session. A missing or damaged file gives the defaults, as does one over [MaxBytes] (audit BD-03: it
 * is read whole). Shared by the Android app and the iOS app (FBBD-359).
 */
class CoachSettingsStore(private val file: String) {
    fun read(): CoachSettings = try {
        if (CoreFiles.isFile(file) && CoreFiles.size(file) <= MaxBytes) {
            CoachSettingsFile.decode(CoreFiles.readBytes(file))
        } else {
            CoachSettings()
        }
    } catch (_: IOException) {
        CoachSettings()
    }

    /** Writes [settings] through a synced temporary file and a rename; throws [IOException] when it cannot. */
    @Throws(IOException::class)
    fun write(settings: CoachSettings) {
        CoreFiles.writeBytesAtomically(file, CoachSettingsFile.encode(settings))
    }

    companion object {
        /** Far above the few lines the app writes. */
        const val MaxBytes = 1L shl 20
    }
}
