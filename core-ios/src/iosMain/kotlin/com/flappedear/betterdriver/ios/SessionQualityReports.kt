package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.analysis.quality.QualityReport
import com.flappedear.betterdriver.analysis.quality.QualityReportFiles
import com.flappedear.betterdriver.analysis.quality.logQuality
import com.flappedear.betterdriver.feature.analysis.QualityUi
import com.flappedear.betterdriver.feature.analysis.qualityUi
import platform.Foundation.NSDate
import platform.Foundation.NSLock
import platform.Foundation.timeIntervalSince1970
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync

/**
 * The validation report of the sessions in [sessionsDir] on the iPhone and iPad (FBBD-183, FBBD-365),
 * kept as quality.json in each session's folder by the shared core, as on Android: the Data quality
 * card of the session's Summary, and the report's lines in the log when a recording stops. Timed
 * against the saved tracks in [tracksDir]. iOS counts no frames in the video files, so the frame
 * check of a phone file is not measured here. Blocking: call it off the main thread.
 */
class SessionQualityReports(sessionsDir: String, tracksDir: String) {
    private val files = QualityReportFiles(sessionsDir, { TrackFiles.read(tracksDir) }, ::nowMillis)

    /**
     * The report of session [id], made the first time; null while it records or when it cannot be
     * read. One at a time across the app, so the stop and the screen never make the same one twice.
     */
    fun report(id: String): QualityReport? {
        lock.lock()
        try {
            return files.load(id)
        } finally {
            lock.unlock()
        }
    }

    /** The Data quality card of session [id] (FBBD-183), as Android's; null when there is no report. */
    fun card(id: String): QualityUi? = report(id)?.let(::qualityUi)

    /**
     * Writes the report of recording [id] into the log once it has stopped (FBBD-183), on a queue of
     * its own as Android's recorder does on its control thread; it is then also ready for the screen.
     */
    fun logAtStop(id: String) = dispatch_async(queue) {
        report(id)?.let { logQuality(id, it) }
    }

    /** Waits for the reports queued by [logAtStop], for tests. */
    internal fun awaitLogged() = dispatch_sync(queue) {}

    private companion object {
        /** One report at a time in the whole app, as Android's QualityReports. */
        val lock = NSLock()
        val queue = dispatch_queue_create("quality", null)
    }
}

private const val MillisPerSecond = 1000.0

private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * MillisPerSecond).toLong()
