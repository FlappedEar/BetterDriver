package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.Provenance

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
            val text = head.decodeToString().removePrefix(Bom)
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
// An IOException on the JVM, as before the import was common code (FBBD-331).
expect class ImportFormatException(message: String, cause: Throwable? = null) : Exception

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

/**
 * An extra column a file carries (a VBO or CSV channel that is not a position value). [provenance]
 * is where the file says its values come from, null when it does not say (FBBD-174).
 */
data class ExtraColumn(val name: String, val unit: String, val provenance: Provenance? = null)

/** Where a reader puts what it finds. [extras] comes before the first [point] if at all. */
interface ImportSink {
    fun extras(columns: List<ExtraColumn>)
    fun point(p: ImportPoint)

    /** A row or point that could not be read and was left out. */
    fun skipped()

    /** The device or app the file names as its maker, such as a GPX file's creator (FBBD-174). */
    fun receiver(name: String) = Unit

    /** Where the file says its acceleration columns come from, when it says (FBBD-174). */
    fun accelerationSource(provenance: Provenance) = Unit

    /** Where the file says a GNSS channel's values (by its GnssChannels id) come from, when it says (FBBD-174). */
    fun channelSource(channelId: Int, provenance: Provenance) = Unit
}

/**
 * Reads lines up to [ImportLimits.MaxLineChars]; a longer line fails the import instead of filling memory.
 * A byte order mark before the first line is dropped, so that line reads as the text it holds.
 * [read] fills the buffer it is given and returns the chars read, or -1 (or 0) at the end, as
 * java.io.Reader.read(CharArray) does.
 */
internal class LineReader(private val read: (CharArray) -> Int) {
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
        // Read again after a first read of the byte order mark alone: that is not the end of the file.
        while (pos >= end) {
            end = read(buf).coerceAtLeast(0)
            pos = 0
            if (end == 0) return false
            if (!started) {
                started = true
                if (buf[0] == Bom) pos = 1
            }
        }
        return true
    }

    private companion object {
        const val BufferChars = 64 * 1024
        const val Bom = '﻿'
    }
}

/**
 * UTF-8 text from bytes, a piece at a time, for platforms without java.io.Reader (iOS, FBBD-331).
 * [readBytes] fills (bytes, offset, length) and returns the bytes read, or -1 (or 0) at the end.
 * A character split between two pieces is kept whole; bytes that are not UTF-8 read as U+FFFD,
 * as with InputStreamReader.
 */
internal class Utf8Chars(private val readBytes: (ByteArray, Int, Int) -> Int) {
    private var bytes = ByteArray(0)
    private var carry = 0
    private var ended = false

    /** Fills [out] with up to its size in chars; returns how many, or -1 at the end. */
    fun read(out: CharArray): Int {
        if (bytes.size != out.size) bytes = bytes.copyOf(out.size.coerceAtLeast(MaxSequence))
        var text = ""
        while (text.isEmpty() && !ended) {
            val n = readBytes(bytes, carry, bytes.size - carry)
            if (n <= 0) {
                ended = true
            } else {
                val total = carry + n
                val cut = boundary(total)
                carry = total - cut
                text = bytes.decodeToString(0, cut)
                bytes.copyInto(bytes, 0, cut, total)
            }
        }
        if (text.isEmpty() && carry > 0) {
            // What is left at the end is a cut-off character.
            text = bytes.decodeToString(0, carry)
            carry = 0
        }
        return if (text.isEmpty()) -1 else copy(text, out)
    }

    /** UTF-8 never takes more chars than bytes, so [text] fits [out]. */
    private fun copy(text: String, out: CharArray): Int {
        for (i in text.indices) out[i] = text[i]
        return text.length
    }

    /** Where the last whole character among the first [total] bytes ends. */
    private fun boundary(total: Int): Int {
        var i = total - 1
        while (i > 0 && total - i < MaxSequence && isContinuation(bytes[i])) i--
        val lead = bytes[i].toInt() and ByteMask
        val length = when {
            lead and TwoMask == TwoLead -> TwoBytes
            lead and ThreeMask == ThreeLead -> ThreeBytes
            lead and FourMask == FourLead -> MaxSequence
            else -> 1
        }
        return if (i + length > total) i else total
    }

    private fun isContinuation(b: Byte) = b.toInt() and ContinuationMask == ContinuationBits

    private companion object {
        const val MaxSequence = 4
        const val TwoBytes = 2
        const val ThreeBytes = 3
        const val ByteMask = 0xFF
        const val ContinuationMask = 0xC0
        const val ContinuationBits = 0x80
        const val TwoMask = 0xE0
        const val TwoLead = 0xC0
        const val ThreeMask = 0xF0
        const val ThreeLead = 0xE0
        const val FourMask = 0xF8
        const val FourLead = 0xF0
    }
}

private const val DaysPer400Years = 146_097L
private const val DaysTo1970 = 719_468L
private const val MonthsPerYear = 12
private const val February = 2
private const val DaysInFebruary = 28
private const val DaysInLongMonth = 31
private const val DaysInShortMonth = 30

/**
 * Days from 1970-01-01 to [year]-[month]-[day] (proleptic Gregorian, as java.time.LocalDate's
 * toEpochDay), or null for a date that does not exist, such as 31/04 or 29/02 of a common year.
 */
@Suppress("MagicNumber") // The civil-calendar arithmetic of Howard Hinnant's days_from_civil.
internal fun epochDayOf(year: Int, month: Int, day: Int): Long? {
    if (month !in 1..MonthsPerYear || day < 1 || day > daysInMonth(year, month)) return null
    val y = (if (month <= February) year - 1 else year).toLong()
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * DaysPer400Years + doe - DaysTo1970
}

@Suppress("MagicNumber") // The Gregorian leap year rule and the 30-day months.
private fun daysInMonth(year: Int, month: Int): Int = when (month) {
    February -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) DaysInFebruary + 1 else DaysInFebruary
    4, 6, 9, 11 -> DaysInShortMonth
    else -> DaysInLongMonth
}
