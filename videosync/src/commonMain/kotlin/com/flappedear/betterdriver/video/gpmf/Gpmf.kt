@file:Suppress("MagicNumber") // GPMF type sizes and the GPS5/GPS9 field layouts.

package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4FormatException
import com.flappedear.betterdriver.video.mp4.ByteReader
import com.flappedear.betterdriver.video.mp4.Mp4Info
import com.flappedear.betterdriver.video.mp4.Mp4Parser
import com.flappedear.betterdriver.video.mp4.Mp4Source
import com.flappedear.betterdriver.video.mp4.ascii
import com.flappedear.betterdriver.video.mp4.parsing
import kotlin.math.roundToLong

/** [GpsSample.fix] when the stream has no fix information. */
const val UnknownFix = -1

/**
 * One GoPro GPS sample.
 *
 * [videoTimeUs] is its time in the video, from the MP4 sample table. [utcMillis] is the GPS UTC
 * time: per sample for GPS9 (HERO11 and later), or for GPS5 (HERO5 to HERO11) the payload's GPSU
 * time spread over its samples, which is only roughly the time of the first sample.
 * [fix] is 0 (none), 2 (2D), 3 (3D) or [UnknownFix] when the stream does not say.
 */
data class GpsSample(
    val videoTimeUs: Long,
    val utcMillis: Long?,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val fix: Int,
)

/**
 * Reads the GPS track from GoPro GPMF telemetry.
 *
 * GPMF is a tree of KLV items: a FourCC key, a 1-byte type, a 1-byte structure size and a 2-byte
 * big-endian repeat count, then structure size × repeat bytes of big-endian data padded to 32 bits.
 * Type 0 means the value is nested KLV. Each payload is DEVC → STRM → items; SCAL divides the
 * next data item. GPS5 holds lat, lon, alt, 2D speed, 3D speed; GPS9 adds days since 2000,
 * seconds since midnight, DOP and fix. Source: https://github.com/gopro/gpmf-parser (README).
 */
object GpmfGps {

    /**
     * The GPS samples of the file [source] reads, whose index is [info]; a damaged file throws
     * [Mp4FormatException] (see its parser contract).
     */
    fun read(source: Mp4Source, info: Mp4Info): List<GpsSample> {
        val track = info.gpmfTrack ?: return emptyList()
        return track.samples.flatMap { sample ->
            parsePayload(Mp4Parser.readSample(source, sample), sample.startUs, sample.durationUs)
        }
    }

    /** The GPS samples of the file [name] that [source] reads. */
    fun read(source: Mp4Source, name: String): List<GpsSample> = read(source, Mp4Parser.read(source, name))

    /**
     * GPS samples in one GPMF payload that plays from [startUs] for [durationUs]. A damaged payload
     * throws [Mp4FormatException], never another exception.
     */
    fun parsePayload(payload: ByteArray, startUs: Long, durationUs: Long): List<GpsSample> = parsing("GPMF payload") {
        val out = mutableListOf<GpsSample>()
        Gpmf.forEachStream(ByteReader(payload)) { items -> out += gpsFromStream(items, startUs, durationUs) }
        out
    }

    private fun gpsFromStream(items: List<Klv>, startUs: Long, durationUs: Long): List<GpsSample> {
        val gps9 = items.firstOrNull { it.key == "GPS9" }
        val data = gps9 ?: items.firstOrNull { it.key == "GPS5" }
        if (data == null || data.repeat == 0) return emptyList()
        val scal = scaleFor(items, data)
        val step = durationUs / data.repeat
        return if (data === gps9) {
            gps9Samples(data, items, scal, startUs, step)
        } else {
            gps5Samples(data, items, scal, startUs, step)
        }
    }

    private fun gps5Samples(
        data: Klv,
        items: List<Klv>,
        scal: List<Double>,
        startUs: Long,
        step: Long,
    ): List<GpsSample> {
        val gpsu = items.firstOrNull { it.key == "GPSU" }?.let(::parseGpsu)
        val fix = items.firstOrNull { it.key == "GPSF" }?.let { Gpmf.numbers(it).firstOrNull()?.toInt() }
            ?: Gps5UnknownFix
        val values = Gpmf.numbers(data, Gps5Types)
        checkValues(data, values, Gps5Fields)
        return List(data.repeat) { i ->
            val v = DoubleArray(Gps5Fields) { f -> values[i * Gps5Fields + f] / scal.at(f) }
            GpsSample(
                videoTimeUs = startUs + i * step,
                utcMillis = gpsu?.plus(i * step / MicrosPerMilli),
                lat = v[0],
                lon = v[1],
                speedMps = v[3],
                fix = fix,
            )
        }
    }

    private fun gps9Samples(
        data: Klv,
        items: List<Klv>,
        scal: List<Double>,
        startUs: Long,
        step: Long,
    ): List<GpsSample> {
        val types = items.firstOrNull { it.key == "TYPE" }
            ?.let { it.bytes.ascii().trimEnd('\u0000') }
            ?: Gps9DefaultTypes
        val fields = types.length
        // Checked before any value is read: an empty TYPE once made the reader loop forever (audit A07).
        if (fields < Gps9Fields) throw Mp4FormatException("GPS9 TYPE \"$types\" has $fields fields, needs $Gps9Fields")
        val values = Gpmf.numbers(data, types)
        checkValues(data, values, fields)
        return List(data.repeat) { i ->
            val v = DoubleArray(Gps9Fields) { f -> values[i * fields + f] / scal.at(f) }
            val days = v[5].toLong()
            if (days !in 0..MaxGps9Days) throw Mp4FormatException("GPS9 date $days days after 2000")
            val secondsOfDay = v[6]
            GpsSample(
                videoTimeUs = startUs + i * step,
                utcMillis = Gps9EpochMillis + days * MillisPerDay + (secondsOfDay * MillisPerSecond).roundToLong(),
                lat = v[0],
                lon = v[1],
                speedMps = v[3],
                fix = v[8].toInt(),
            )
        }
    }

    /** Fails unless [data] holds [fields] values for each of its samples. */
    private fun checkValues(data: Klv, values: List<Double>, fields: Int) {
        if (values.size < data.repeat * fields) {
            throw Mp4FormatException("${data.key} holds ${values.size} values for ${data.repeat} samples of $fields")
        }
    }

    /** The SCAL item that comes before [data] in the same stream, or 1 for every field. */
    private fun scaleFor(items: List<Klv>, data: Klv): List<Double> =
        items.subList(0, items.indexOf(data)).lastOrNull { it.key == "SCAL" }?.let { Gpmf.numbers(it) } ?: listOf(1.0)

    private fun List<Double>.at(field: Int): Double = (if (size == 1) this[0] else getOrNull(field) ?: 1.0)
        .takeIf { it != 0.0 } ?: 1.0

    /** "yymmddhhmmss.sss" in UTC, as type 'U' or 'c'. */
    internal fun parseGpsu(item: Klv): Long? = gpsuMillis(item.bytes.ascii().trimEnd('\u0000', ' ').take(GpsuChars))

    private const val Gps5Fields = 5
    private const val Gps9Fields = 9
    private const val Gps5Types = "lllll"
    private const val Gps9DefaultTypes = "lllllllSS"
    private const val Gps5UnknownFix = UnknownFix
    private const val GpsuChars = 16

    /** GPS9 days since 2000 up to the year 2200; anything later is damage. */
    private const val MaxGps9Days = 200L * 366
    private const val MicrosPerMilli = 1000L
    private const val MillisPerSecond = 1000.0

    /** 2000-01-01T00:00Z, GPS9's day 0. */
    private const val Gps9EpochMillis = 946_684_800_000L
    private const val MillisPerDay = 86_400_000L
}

/**
 * "yymmddhhmmss.sss" in UTC as epoch milliseconds, read as java.time's pattern "yyMMddHHmmss.SSS"
 * reads it (years 2000 to 2099; a day past the month's end is its last day, and 24:00:00.000 is the
 * next midnight), or null for text that is not such a time.
 */
internal fun gpsuMillis(text: String): Long? {
    val digits = text.removeRange(DotAt.coerceAtMost(text.length), (DotAt + 1).coerceAtMost(text.length))
    val shaped = text.length == GpsuLength && text[DotAt] == '.' && digits.all { it in '0'..'9' }
    return if (shaped) gpsuDigitsMillis(digits) else null
}

/** [gpsuMillis] of the 15 digits of such a time, without its dot. */
private fun gpsuDigitsMillis(digits: String): Long? {
    fun field(at: Int, n: Int) = digits.substring(at, at + n).toInt()
    val year = 2000 + field(0, 2)
    val month = field(2, 2)
    val hour = field(6, 2)
    val minute = field(8, 2)
    val second = field(10, 2)
    val millis = field(12, 3)
    val endOfDay = hour == 24 && minute == 0 && second == 0 && millis == 0
    val time = (hour in 0..23 || endOfDay) && minute in 0..59 && second in 0..59
    val valid = month in 1..12 && field(4, 2) in 1..31 && time
    val day = minOf(field(4, 2), daysIn(year, month))
    val seconds = ((daysFromEpoch(year, month, day) * 24 + hour) * 60 + minute) * 60L + second
    return if (valid) seconds * 1000 + millis else null
}

private const val GpsuLength = 16
private const val DotAt = 12

private fun daysIn(year: Int, month: Int): Int = when (month) {
    2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** Days from 1970-01-01 to the date, proleptic Gregorian (H. Hinnant's days_from_civil). */
internal fun daysFromEpoch(year: Int, month: Int, day: Int): Long {
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}
