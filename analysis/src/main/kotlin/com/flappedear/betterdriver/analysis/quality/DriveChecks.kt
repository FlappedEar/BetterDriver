package com.flappedear.betterdriver.analysis.quality

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.EventKinds
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.log.GnssSecond
import com.flappedear.betterdriver.recorder.log.LogLine
import com.flappedear.betterdriver.recorder.store.Record
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import java.io.File
import java.util.zip.ZipFile

// The hardware checks of every drive, worked out from the app's continuous log alone (FBBD-183):
// the entry point for what Arek shares after a drive. Each recording in the log gets a report with
// the same sections and values as the one the app stores next to the session, each marked PASS,
// FAIL or NOT_MEASURED with the FBBD issues it checks.

/**
 * The reports of every recording in [lines], in the order they started. The app's cold starts and
 * ANRs (FBBD-41) belong to no recording: each report grades the cold starts logged since the
 * recording before it ended, and the ANRs while it ran.
 */
fun driveChecks(lines: List<LogLine>): List<QualityReport> {
    val life = AppLife.of(lines)
    var since: Long? = null
    val sessions = DriveLog.sessions(lines)
    return sessions.mapIndexed { i, s ->
        val nextStart = sessions.getOrNull(i + 1)?.startUtcMillis
        // The end of a recording without a stop line is inferred, as its report infers it.
        driveChecks(s, life, since, nextStart).also { since = maxOf(since ?: Long.MIN_VALUE, endUtcMillis(s)) }
    }
}

/**
 * The reports of every recording in a shared log: a zip as Share log makes it, a single log file, or
 * a folder of log files. Older builds' per-process files are named after their process.
 */
fun driveChecks(file: File): List<QualityReport> = driveChecks(readLog(file))

/** The log lines of [file]: a zip, a folder or one file; see [driveChecks]. */
fun readLog(file: File): List<LogLine> = when {
    file.isDirectory -> file.listFiles().orEmpty().filter { it.name.endsWith(".log") }
        .flatMap { readLog(it) }
    file.name.endsWith(".zip") -> ZipFile(file).use { zip ->
        zip.entries().toList().filter { it.name.endsWith(".log") }.flatMap { entry ->
            zip.getInputStream(entry).bufferedReader().useLines { DriveLog.parse(it, processOf(entry.name)) }
        }
    }
    else -> file.bufferedReader().useLines { DriveLog.parse(it, processOf(file.name)) }
}

/** "recorder.1.log" was written by the recorder process. */
private fun processOf(name: String) = name.substringAfterLast('/').substringBefore('.')

/** The report of one recording from the log alone, without the app's starts and process ends around it. */
fun driveChecks(s: LoggedSession): QualityReport =
    driveChecks(s, AppLife(emptyList(), emptyList(), emptyList()), null, null)

/**
 * The report of one recording; [life] and [sinceUtc] as [driveChecks] of the whole log gives them,
 * [nextStartUtc] the start of the recording after it, if any.
 */
private fun driveChecks(s: LoggedSession, life: AppLife, sinceUtc: Long?, nextStartUtc: Long?): QualityReport {
    val seconds = s.seconds.sortedBy { it.tMicros }
    val events = s.events.sortedBy { it.tMicros }
    val sets = seconds.map(::sampleSet)
    val end = s.inferredEndMicros()
    val data = SessionData(
        s.id, SessionInfo(s.startUtcMillis ?: 0L, devices = listOfNotNull(s.source)), SessionState.FINISHED,
        emptyMap(), sets, events, Record.End(end, s.stopReason ?: "unknown"), truncatedBytes = 0, skippedBytes = 0,
    )
    val samples = GnssSamples(sets)
    val first = seconds.firstOrNull()?.tMicros ?: 0L
    val last = seconds.lastOrNull()?.tMicros ?: end
    val clips = importedClips(events)
    val sections = listOfNotNull(
        if (s.legacy) legacyGnssSection(s, end) else logGnssSection(seconds, events, end),
        timingSection(data, null),
        recordingSection(data, samples, null),
        videoSection(events, clips, first, last),
        goProSection(events, clips, end),
        coachSection(events, samples),
        thermalSection(events, end),
        screenSection(events, logOutages(seconds, end), end),
        exportSection(events, end),
        startupSection(
            life, sinceUtc, s.startUtcMillis ?: 0L, endUtcMillis(s),
            unfinishedUntilUtc = if (s.endMicros == null) nextStartUtc ?: Long.MAX_VALUE else null,
        ),
    )
    return QualityReport(QualityFormat, s.id, "LOGGED", s.startUtcMillis ?: 0L, withSoak(sections, end))
}

/** When [s] ended on the phone's wall clock, as [LoggedSession.inferredEndMicros] gives it. */
private fun endUtcMillis(s: LoggedSession): Long = (s.startUtcMillis ?: 0L) + s.inferredEndMicros() / MicrosPerMilli

/** One sample per logged second: its first sample's time, the receiver time, the mean fix and speed. */
private fun sampleSet(g: GnssSecond) = SampleSet(
    g.tMicros,
    listOfNotNull(
        g.utcFirst?.let { ChannelValue(GnssChannels.UTC_MS.id, it.toDouble()) },
        g.lat?.let { ChannelValue(GnssChannels.LAT.id, it) },
        g.lon?.let { ChannelValue(GnssChannels.LON.id, it) },
        g.kmh?.let { ChannelValue(GnssChannels.SPEED_KMH.id, it) },
    ),
)

/** Imported files as [EventKinds.VIDEO_IMPORT] lines name them, placed on the session's timeline. */
private fun importedClips(events: List<com.flappedear.betterdriver.recorder.RecorderEvent>): List<QualityClip> =
    events.ofKind(EventKinds.VIDEO_IMPORT).mapNotNull { e ->
        val f = fields(e.text).takeIf { it["result"] == "aligned" }.orEmpty()
        val offset = f["offset_us"]?.toLongOrNull()
        val camera = f["camera"]
        val file = f["file"]
        if (offset == null || camera == null || file == null) {
            null
        } else {
            val duration = f["duration_us"]?.toLongOrNull()
            QualityClip(camera, file, offset, duration?.let { offset + it }, f["method"].orEmpty(), 0L)
        }
    }

/** Waits between fixes over the banner's threshold: each ends at the first fix of the second that logged it. */
private fun logOutages(seconds: List<GnssSecond>, endMicros: Long): List<Outage> {
    val out = seconds.mapNotNull { g ->
        val gap = g.maxGapMicros
        val back = g.firstFixMicros
        if (gap != null && back != null && gap > BannerThresholdMicros) Outage(back - gap, back) else null
    }
    val lastFix = seconds.lastOrNull { it.fixes > 0 }?.let { it.tMicros }
    return if (lastFix != null && endMicros - lastFix > BannerThresholdMicros) out + Outage(lastFix, null) else out
}
