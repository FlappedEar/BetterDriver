package com.flappedear.betterdriver.recorder.export

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.store.SessionData
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes a stored session (finished, recovered or interrupted) as CSV (FBBD-150), for spreadsheets
 * and tools such as RaceChrono's CSV import: UTF-8, comma separated, '.' decimals, CRLF line ends
 * (RFC 4180), fields quoted only when they need it.
 *
 * One row per sample set, in time order. Columns: the time in the phone's time zone with its offset
 * from UTC (ISO-8601, e.g. 2026-10-13T09:11:14.495+02:00, from the session start plus the recorder's
 * clock, so it never runs backwards; FBBD-217), set on the receiver's GNSS clock when the session has
 * fixes with UTC, so the column matches other apps' files (FBBD-324; [gnssClockOffsetMillis]),
 * the seconds since the session started,
 * with [laps] the lap number and the time into that lap, then every channel that has a value, as
 * "name (unit)": the GNSS channels first, then the others by id. A cell is empty when the set has
 * no value for that channel; values are not repeated from earlier rows, so each row is what was
 * measured at that instant. Rows go out one at a time through a buffer.
 */
object CsvExporter {

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
    private const val TimeColumn = "time"

    fun export(
        session: SessionData,
        out: OutputStream,
        laps: List<ExportLap> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), BufferChars)
        write(session, writer, laps, zone)
        writer.flush()
    }

    fun write(
        session: SessionData,
        out: Writer,
        laps: List<ExportLap> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        val time = TIME.withZone(zone)
        val startMillis = session.info.startUtcMillis + (gnssClockOffsetMillis(session) ?: 0L)
        // Sources queue independently, so file order is not time order. The sort is stable.
        val sets = session.sampleSets.sortedBy { it.tMicros }
        val seen = HashSet<Int>()
        for (set in sets) for (v in set.values) seen += v.channelId
        val channels = columnsOf(session.channels, seen)
        val column = HashMap<Int, Int>()
        channels.forEachIndexed { i, c -> column[c.id] = i }

        val header = mutableListOf(TimeColumn, "elapsed (s)")
        if (laps.isNotEmpty()) header += listOf("lap", "lap time (s)")
        header += headerNames(channels)
        line(out, header.joinToString(",") { field(it) })

        val cursor = LapCursor(laps)
        val cells = arrayOfNulls<String>(channels.size)
        val sb = StringBuilder()
        for (set in sets) {
            cells.fill(null)
            for (v in set.values) {
                val i = column[v.channelId] ?: continue
                if (v.value.isFinite()) cells[i] = decimal(v.value, channels[i].decimals)
            }
            sb.setLength(0)
            sb.append(time.format(Instant.ofEpochMilli(startMillis + set.tMicros / MicrosPerMilli)))
            sb.append(',').append(seconds(set.tMicros))
            if (laps.isNotEmpty()) {
                val lap = cursor.at(set.tMicros)
                sb.append(',').append(lap?.number?.toString().orEmpty())
                sb.append(',').append(lap?.let { seconds(set.tMicros - it.startMicros) }.orEmpty())
            }
            for (c in cells) sb.append(',').append(c.orEmpty())
            line(out, sb)
        }
        out.flush()
    }

    /**
     * How far the phone clock that set the session start is behind the receiver's GNSS UTC, in ms:
     * the median over the fixes of UTC minus (start + recorder time), or null with no usable UTC.
     * One offset for the whole file keeps the times from running backwards (FBBD-217). The phone
     * clock was measured 1.944 s ahead of the RaceBox (FBBD-324). Only fixes with a position count,
     * and a UTC before 2000 is a time of day or garbage, as in the GPX export.
     */
    internal fun gnssClockOffsetMillis(session: SessionData): Long? {
        val offsets = session.sampleSets.mapNotNull { set ->
            var utc: Double? = null
            var lat = 0.0
            var lon = 0.0
            for (v in set.values) when (v.channelId) {
                GnssChannels.UTC_MS.id -> utc = v.value
                GnssChannels.LAT.id -> lat = v.value
                GnssChannels.LON.id -> lon = v.value
            }
            val ms = utc?.takeIf { it.isFinite() }?.let { Math.round(it) }?.takeIf { it >= Year2000Millis }
            if (ms == null || (lat == 0.0 && lon == 0.0)) null
            else ms - (session.info.startUtcMillis + set.tMicros / MicrosPerMilli)
        }
        if (offsets.isEmpty()) return null
        return offsets.sorted()[offsets.size / 2]
    }

    /** Channels with values: the GNSS ones in their usual order, then the rest by id. */
    private fun columnsOf(defined: Map<Int, ChannelDef>, seen: Set<Int>): List<ChannelDef> {
        val gnss = GnssChannels.ALL.filter { it.id in seen }.map { defined[it.id] ?: it }
        val other = seen.filter { it !in GnssChannels.IDS }.sorted().map { id ->
            // A value with no definition (a damaged channel table) still exports, under its id.
            defined[id] ?: ChannelDef(id, "ch$id", "", "", DefaultDecimals)
        }
        return gnss + other
    }

    /** "gnss_speed (km/h)"; a name used twice gets its channel id, so every column can be told apart. */
    private fun headerNames(channels: List<ChannelDef>): List<String> {
        val used = mutableSetOf(TimeColumn, "elapsed (s)", "lap", "lap time (s)")
        return channels.map { c ->
            val base = c.name.ifBlank { "ch${c.id}" }
            val withUnit = { name: String -> if (c.unit.isBlank()) name else "$name (${c.unit})" }
            var name = withUnit(base)
            var attempt = 0
            while (!used.add(name)) {
                attempt++
                name = withUnit(if (attempt == 1) "${base}_${c.id}" else "${base}_${c.id}_$attempt")
            }
            name
        }
    }

    /** A field quoted as RFC 4180 asks when it holds a comma, a quote or a line break. */
    internal fun field(s: String): String =
        if (s.any { it in SPECIAL }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private const val SPECIAL = ",\"\r\n"

    private fun decimal(v: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", v)

    private fun seconds(micros: Long) = String.format(Locale.ROOT, "%.3f", micros / MicrosPerSecond)

    private fun line(out: Writer, s: CharSequence) {
        out.append(s)
        out.write(CRLF)
    }

    private const val CRLF = "\r\n"
    private const val MicrosPerMilli = 1_000L
    private const val Year2000Millis = 946_684_800_000L
    private const val MicrosPerSecond = 1_000_000.0
    private const val DefaultDecimals = 3
    private const val BufferChars = 1 shl 16
}
