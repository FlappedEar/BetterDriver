package com.flappedear.betterdriver.transfer.foreign

import java.io.IOException
import java.io.InputStream
import java.io.Reader

/** A session file from another app that [SessionImport] can turn into a session (FBBD-164). */
enum class ForeignFormat(val label: String) {
    /** Racelogic VBO, as Racelogic loggers, Circuit Tools, RaceChrono and this app write it. */
    VBO("VBO"),

    /** GPX 1.1 (and 1.0) track points with times. */
    GPX("GPX"),

    /** RaceChrono's CSV v3 export. */
    RACECHRONO_CSV("RaceChrono CSV"),
    ;

    companion object {
        /** Bytes [detect] looks at. */
        const val PeekBytes = 8 * 1024

        private const val Bom = "﻿"

        /**
         * The format of a file from its first bytes (decompressed), or null. Decided by content, not by
         * the file name: a VBO renamed .txt or a GPX saved as .xml still imports. A NUL byte means binary.
         */
        fun detect(head: ByteArray): ForeignFormat? {
            val text = head.toString(Charsets.UTF_8).removePrefix(Bom)
            val lines = text.lineSequence().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toList()
            val first = lines.firstOrNull().orEmpty()
            return when {
                first.isEmpty() || head.any { it == 0.toByte() } -> null
                first.startsWith("<?xml") || first.startsWith("<gpx") || first.startsWith("<!--") ->
                    if (text.contains("<gpx", ignoreCase = true)) GPX else null
                first.startsWith("file created on") || "[header]" in lines -> VBO
                first.contains("racechrono") || lines.any { it == "format,3" } -> RACECHRONO_CSV
                lines.any(::isCsvHeader) -> RACECHRONO_CSV
                else -> null
            }
        }

        private fun isCsvHeader(line: String): Boolean {
            val cells = line.split(',').map { it.trim().trim('"') }
            return "timestamp" in cells && "latitude" in cells && "longitude" in cells
        }
    }
}

/** A file that cannot be imported: not a known format, damaged, empty or too large. The message is for the user. */
class ImportFormatException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Limits on what one import reads, so a huge or hostile file cannot exhaust the phone. */
object ImportLimits {
    /** File size read, after decompressing: an hour at 25 Hz as VBO is about 10 MB. */
    const val MaxMegabytes = 512

    const val MaxBytes = MaxMegabytes.toLong() shl 20

    /** Positions in one session: a day at 25 Hz is 2.16 million. */
    const val MaxPoints = 2_500_000

    /** Characters in one line of a text format; a longer one means it is not that format. */
    const val MaxLineChars = 64 * 1024

    /** Extra (non-position) columns kept from a VBO or CSV. */
    const val MaxExtraChannels = 64
}

/**
 * One position read from a file. Values a file lacks are NaN. [utcMicros] is absolute UTC.
 * [segment] goes up where a file starts a new track part (GPX trkseg), so speed is never derived
 * across a gap.
 */
@Suppress("LongParameterList") // One value per channel a file can carry, NaN when it does not.
class ImportPoint(
    val utcMicros: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double = Double.NaN,
    val headingDeg: Double = Double.NaN,
    val altM: Double = Double.NaN,
    val sats: Double = Double.NaN,
    /** Forward (longitudinal) acceleration, g. */
    val accelForwardG: Double = Double.NaN,
    /** Lateral acceleration, g. */
    val accelLateralG: Double = Double.NaN,
    /** Values of the reader's extra columns, in [ImportSink.extras] order; NaN where missing. */
    val extras: DoubleArray = EmptyValues,
    val segment: Int = 0,
) {
    fun copy(speedKmh: Double = this.speedKmh, headingDeg: Double = this.headingDeg) = ImportPoint(
        utcMicros, lat, lon, speedKmh, headingDeg, altM, sats, accelForwardG, accelLateralG, extras, segment,
    )

    companion object {
        val EmptyValues = DoubleArray(0)
    }
}

/** An extra column a file carries (a VBO or CSV channel that is not a position value). */
data class ExtraColumn(val name: String, val unit: String)

/** Where a reader puts what it finds. [extras] comes before the first [point] if at all. */
interface ImportSink {
    fun extras(columns: List<ExtraColumn>)
    fun point(p: ImportPoint)

    /** A row or point that could not be read and was left out. */
    fun skipped()
}

/**
 * Reads lines up to [ImportLimits.MaxLineChars]; a longer line fails the import instead of filling memory.
 * A byte order mark before the first line is dropped, so that line reads as the text it holds.
 */
internal class LineReader(private val reader: Reader) {
    private val buf = CharArray(BufferChars)
    private var pos = 0
    private var end = 0
    private var started = false

    /** The next line without its terminator, or null at the end. */
    fun next(): String? {
        val sb = StringBuilder()
        while (fill()) {
            val c = buf[pos++]
            if (c == '\n') return sb.toString()
            if (c != '\r') {
                if (sb.length >= ImportLimits.MaxLineChars) throw ImportFormatException("a line is too long")
                sb.append(c)
            }
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    /** Makes sure a char is buffered; false at the end. */
    private fun fill(): Boolean {
        if (pos < end) return true
        end = reader.read(buf).coerceAtLeast(0)
        pos = 0
        if (!started) {
            started = true
            if (end > 0 && buf[0] == Bom) pos = 1
        }
        return pos < end
    }

    private companion object {
        const val BufferChars = 64 * 1024
        const val Bom = '\uFEFF'
    }
}

/**
 * Counts what passes through: [onBytes] for progress, and the import fails beyond [limit] bytes
 * (a decompressed file can be far larger than the one picked).
 */
internal class CountingInput(
    private val input: InputStream,
    private val limit: Long,
    private val onBytes: (Int) -> Unit = {},
) : InputStream() {
    var count = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) counted(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) counted(n)
        return n
    }

    private fun counted(n: Int) {
        count += n
        if (count > limit) throw ImportFormatException("the file is too large (over ${limit / BytesPerMegabyte} MB)")
        onBytes(n)
    }

    override fun close() = input.close()

    private companion object {
        const val BytesPerMegabyte = 1L shl 20
    }
}
