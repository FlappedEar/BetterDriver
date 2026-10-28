package com.flappedear.betterdriver.recorder.export

import com.flappedear.betterdriver.format.fixed
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionState
import kotlin.math.roundToLong

/**
 * Writes a stored session (finished, recovered or interrupted) as GPX 1.1 (FBBD-150), for maps,
 * Strava-like tools and RaceChrono's GPX import.
 *
 * One <trk> for the session. With [laps], one <trkseg> per lap, a comment naming it; points
 * outside every lap get segments of their own. Without laps, one <trkseg> per stretch of fixes:
 * a set without a position (no fix, reported as 0, 0) or a gap of more than [GapMicros] ends one.
 * Each point has lat/lon, <ele> and <sat> when the same set holds them, <time> (the receiver's UTC
 * when the set holds it, as in VBO export, else session start plus the recorder's clock), and speed
 * (m/s) and course in Garmin's TrackPointExtension v2, which Garmin, Strava and most GPX readers
 * understand. Free text is XML-escaped. Points go out one at a time; the caller buffers [Appendable]
 * output. Common code (FBBD-331): Android's GpxExporter and the iPhone's export both write through it.
 */
object GpxWriter {

    /** A longer pause between fixes than this starts a new segment when laps are unknown. */
    const val GapMicros = 5_000_000L

    /**
     * Writes [session] to [out]; [utcTime] gives a UTC time in ms as GPX writes it, ISO-8601 in UTC
     * with milliseconds (2026-10-13T07:11:14.495Z).
     */
    fun write(session: SessionData, out: Appendable, laps: List<ExportLap>, utcTime: (utcMillis: Long) -> String) {
        val sets = session.sampleSets.sortedBy { it.tMicros }
        header(session, out, utcTime)
        val segments = Segments(laps)
        for (set in sets.filter { it.reportsPosition() }) {
            val p = set.position()
            if (p == null) {
                segments.lost()
                continue
            }
            if (segments.startsNew(set.tMicros)) {
                if (segments.count > 1) out.append("    </trkseg>\n")
                segments.lap?.let { out.append("    <!-- Lap ${it.number} -->\n") }
                out.append("    <trkseg>\n")
            }
            point(session, set, p, out, utcTime)
        }
        if (segments.count > 0) out.append("    </trkseg>\n")
        out.append("  </trk>\n</gpx>\n")
    }

    /** Where segments start: at each lap, or without laps at each lost fix and each gap. */
    private class Segments(private val laps: List<ExportLap>) {
        private val cursor = LapCursor(laps)
        private var key: Any? = null
        private var lastFix: Long? = null

        /** Segments started so far. */
        var count = 0
            private set

        /** The lap of the current segment, if it is one. */
        val lap: ExportLap? get() = key as? ExportLap

        /** A set reported no position: a stretch ends; with laps, the lap's segment carries on. */
        fun lost() {
            lastFix = null
        }

        /** Moves on to a fix at [t]; true when it starts a new segment. */
        fun startsNew(t: Long): Boolean {
            val newKey: Any = if (laps.isEmpty()) Unit else cursor.at(t) ?: NoLap
            val previous = lastFix
            val split = if (laps.isEmpty()) previous == null || t - previous > GapMicros else newKey != key
            lastFix = t
            key = newKey
            // The first fix always splits: no stretch yet, and a lap key is never null.
            if (split) count++
            return split
        }
    }

    private fun header(session: SessionData, out: Appendable, utcTime: (Long) -> String) {
        val info = session.info
        val start = utcTime(info.startUtcMillis)
        val name = info.track.ifBlank { "Session ${session.id}" }
        val desc = buildList {
            if (info.car.isNotBlank()) add("Car ${info.car}")
            if (session.state == SessionState.RECOVERED || session.state == SessionState.INTERRUPTED) {
                add("Recovered after an interrupted recording")
            }
        }.joinToString(". ")
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        out.append(
            "<gpx version=\"1.1\" creator=\"BetterDriver\" xmlns=\"$GpxNs\" xmlns:gpxtpx=\"$TpxNs\"" +
                " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
                " xsi:schemaLocation=\"$GpxNs http://www.topografix.com/GPX/1/1/gpx.xsd" +
                " $TpxNs https://www8.garmin.com/xmlschemas/TrackPointExtensionv2.xsd\">\n",
        )
        out.append("  <metadata>\n    <name>${escape(name)}</name>\n")
        if (desc.isNotEmpty()) out.append("    <desc>${escape(desc)}</desc>\n")
        out.append("    <time>$start</time>\n  </metadata>\n")
        out.append("  <trk>\n    <name>${escape(name)}</name>\n")
        if (desc.isNotEmpty()) out.append("    <desc>${escape(desc)}</desc>\n")
        out.append("    <src>BetterDriver session ${escape(session.id)}</src>\n")
    }

    /** One <trkpt>, its children in the order the GPX 1.1 schema requires. */
    private fun point(session: SessionData, set: SampleSet, p: Position, out: Appendable, utcTime: (Long) -> String) {
        // A receiver clock before 2000 is a time of day or garbage, not a date: use the session clock.
        val utc = set.valueOf(GnssChannels.UTC_MS.id)?.roundToLong()?.takeIf { it >= Year2000Millis }
            ?: (session.info.startUtcMillis + set.tMicros / MicrosPerMilli)
        val sb = StringBuilder(PointChars)
        sb.append("      <trkpt lat=\"").append(fixed(p.lat, LatLonDecimals))
            .append("\" lon=\"").append(fixed(p.lon, LatLonDecimals)).append("\">")
        set.valueOf(GnssChannels.ALT_M.id)?.let { sb.append("<ele>").append(fixed(it, 2)).append("</ele>") }
        sb.append("<time>").append(utcTime(utc)).append("</time>")
        set.valueOf(GnssChannels.SATS.id)?.takeIf { it >= 0 }?.let {
            sb.append("<sat>").append(it.toLong()).append("</sat>")
        }
        val speed = set.valueOf(GnssChannels.SPEED_KMH.id)?.takeIf { it >= 0 }
        val course = set.valueOf(GnssChannels.HEADING.id)?.let { ((it % FullCircle) + FullCircle) % FullCircle }
        if (speed != null || course != null) {
            sb.append("<extensions><gpxtpx:TrackPointExtension>")
            speed?.let {
                sb.append("<gpxtpx:speed>").append(fixed(it / KmhPerMs, SpeedDecimals)).append("</gpxtpx:speed>")
            }
            course?.let { sb.append("<gpxtpx:course>").append(fixed(it, CourseDecimals)).append("</gpxtpx:course>") }
            sb.append("</gpxtpx:TrackPointExtension></extensions>")
        }
        sb.append("</trkpt>\n")
        out.append(sb)
    }

    /**
     * Text for XML content or an attribute: the five special characters escaped, and characters
     * XML 1.0 does not allow (most control characters, lone surrogates) left out.
     */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val entity = ENTITIES[c]
            when {
                entity != null -> sb.append(entity)
                c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> sb.append(c).append(s[++i])
                allowedInXml(c) -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private val ENTITIES = mapOf('&' to "&amp;", '<' to "&lt;", '>' to "&gt;", '"' to "&quot;", '\'' to "&apos;")

    /** XML 1.0 Char, for a single UTF-16 unit: no lone surrogates, no controls but tab, CR and LF. */
    private fun allowedInXml(c: Char) =
        !c.isSurrogate() && c != '\uFFFE' && c != '\uFFFF' && (c >= ' ' || c == '\t' || c == '\n' || c == '\r')

    /** Marks points that fall outside every lap, so they get a segment of their own. */
    private val NoLap = Any()

    private const val GpxNs = "http://www.topografix.com/GPX/1/1"
    private const val TpxNs = "http://www.garmin.com/xmlschemas/TrackPointExtension/v2"
    private const val LatLonDecimals = 8
    private const val SpeedDecimals = 3
    private const val CourseDecimals = 2
    private const val FullCircle = 360.0
    private const val KmhPerMs = 3.6
    private const val MicrosPerMilli = 1_000L
    private const val Year2000Millis = 946_684_800_000L
    private const val PointChars = 256
}
