package com.flappedear.betterdriver.coach

import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.store.SessionSummary
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * How many cues each saved session has (FBBD-99), so the session list can offer the cue review
 * without reading every session each time. A session is read once and its count remembered in
 * [file], keyed by its id and length; an imported session that replaced one is read again.
 * Sessions still recording are left out. Run it off the main thread.
 */
class CoachedSessions(private val sessions: SessionStore, private val file: File) {
    /** Session id to its number of cues, for sessions with at least one. */
    @Synchronized
    fun counts(list: List<SessionSummary> = sessions.list()): Map<String, Int> {
        val known = read()
        val done = list.filter { it.state != SessionState.RECORDING }
        val found = rememberCueCounts(done, { it.id }, { it.durationMicros }, known, ::count)
        if (found.changed) write(known)
        return found.counts
    }

    @Suppress("TooGenericExceptionCaught") // A damaged session counts as no cues; it is never a crash.
    private fun count(s: SessionSummary): Int = try {
        CueReview.count(sessions.read(s.dir, samples = false).events)
    } catch (_: IOException) {
        0
    } catch (_: RuntimeException) {
        0
    }

    private fun read(): MutableMap<String, String> {
        val p = Properties()
        try {
            if (file.isFile) file.inputStream().use(p::load)
        } catch (_: IOException) {
            p.clear()
        } catch (_: IllegalArgumentException) {
            p.clear()
        }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }.toMutableMap()
    }

    private fun write(map: Map<String, String>) {
        file.parentFile?.mkdirs()
        val p = Properties()
        map.forEach { (k, v) -> p.setProperty(k, v) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            tmp.outputStream().use { out ->
                p.store(out, "session id = durationMicros:cues")
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (_: IOException) {
            // Only a cache: the counts are read again next time.
        }
    }
}
