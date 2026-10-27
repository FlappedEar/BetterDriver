package com.flappedear.betterdriver.coach

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * Coach settings in one small file (FBBD-98). The app writes it; the recorder and coach processes
 * read it at each session start, so a change applies from the next session. A missing or damaged
 * file gives the defaults.
 */
class CoachSettingsStore(private val file: File) {
    fun read(): CoachSettings {
        val p = Properties()
        try {
            if (file.isFile) file.inputStream().use(p::load)
        } catch (_: IOException) {
            p.clear()
        } catch (_: IllegalArgumentException) {
            p.clear()
        }
        return CoachSettingsFile.fromMap(p.stringPropertyNames().associateWith { p.getProperty(it) })
    }

    fun write(settings: CoachSettings) {
        file.parentFile?.mkdirs()
        val p = Properties()
        CoachSettingsFile.toMap(settings).forEach { (k, v) -> p.setProperty(k, v) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            p.store(out, "BetterDriver coach settings")
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}
