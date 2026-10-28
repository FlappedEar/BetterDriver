package com.flappedear.betterdriver.recorder.export

import com.flappedear.betterdriver.recorder.store.SessionData
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Writes a stored session as GPX 1.1 (FBBD-150) on the JVM: the common [GpxWriter] to a buffered UTF-8 stream. */
object GpxExporter {

    /** See [GpxWriter.GapMicros]. */
    const val GapMicros = GpxWriter.GapMicros

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun export(session: SessionData, out: OutputStream, laps: List<ExportLap> = emptyList()) {
        val writer = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), BufferChars)
        write(session, writer, laps)
        writer.flush()
    }

    fun write(session: SessionData, out: Writer, laps: List<ExportLap> = emptyList()) {
        GpxWriter.write(session, out, laps) { TIME.format(Instant.ofEpochMilli(it)) }
        out.flush()
    }

    internal fun escape(s: String): String = GpxWriter.escape(s)

    private const val BufferChars = 1 shl 16
}
