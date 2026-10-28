package com.flappedear.betterdriver.recorder.export

import com.flappedear.betterdriver.recorder.store.SessionData
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Writes a stored session as CSV (FBBD-150) on the JVM: the common [CsvWriter], with the time
 * column in [ZoneId]'s time zone and a buffered UTF-8 stream.
 */
object CsvExporter {

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

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
        CsvWriter.write(session, out, laps) { time.format(Instant.ofEpochMilli(it)) }
        out.flush()
    }

    /** See [CsvWriter.gnssClockOffsetMillis]. */
    internal fun gnssClockOffsetMillis(session: SessionData): Long? = CsvWriter.gnssClockOffsetMillis(session)

    internal fun field(s: String): String = CsvWriter.field(s)

    private const val BufferChars = 1 shl 16
}
