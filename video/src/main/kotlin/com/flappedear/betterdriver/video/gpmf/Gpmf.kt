@file:Suppress("MagicNumber") // GPMF type sizes and the GPS5/GPS9 field layouts.

package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4Reader
import java.io.File
import java.nio.ByteBuffer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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

    fun read(file: File): List<GpsSample> {
        val track = Mp4Reader.read(file).gpmfTrack ?: return emptyList()
        return track.samples.flatMap { sample ->
            parsePayload(Mp4Reader.readSample(file, sample), sample.startUs, sample.durationUs)
        }
    }

    /** GPS samples in one GPMF payload that plays from [startUs] for [durationUs]. */
    fun parsePayload(payload: ByteArray, startUs: Long, durationUs: Long): List<GpsSample> {
        val out = mutableListOf<GpsSample>()
        Gpmf.forEachStream(ByteBuffer.wrap(payload)) { items -> out += gpsFromStream(items, startUs, durationUs) }
        return out
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
            ?.let { String(it.bytes, Charsets.US_ASCII).trimEnd('\u0000') }
            ?: Gps9DefaultTypes
        val values = Gpmf.numbers(data, types)
        val fields = types.length
        if (fields < Gps9Fields) return emptyList()
        return List(data.repeat) { i ->
            val v = DoubleArray(Gps9Fields) { f -> values[i * fields + f] / scal.at(f) }
            val days = v[5].toLong()
            val secondsOfDay = v[6]
            GpsSample(
                videoTimeUs = startUs + i * step,
                utcMillis = Gps9Epoch.plusDays(days).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() +
                    Math.round(secondsOfDay * MillisPerSecond),
                lat = v[0],
                lon = v[1],
                speedMps = v[3],
                fix = v[8].toInt(),
            )
        }
    }

    /** The SCAL item that comes before [data] in the same stream, or 1 for every field. */
    private fun scaleFor(items: List<Klv>, data: Klv): List<Double> =
        items.subList(0, items.indexOf(data)).lastOrNull { it.key == "SCAL" }?.let { Gpmf.numbers(it) } ?: listOf(1.0)

    private fun List<Double>.at(field: Int): Double = (if (size == 1) this[0] else getOrNull(field) ?: 1.0)
        .takeIf { it != 0.0 } ?: 1.0

    /** "yymmddhhmmss.sss" in UTC, as type 'U' or 'c'. */
    internal fun parseGpsu(item: Klv): Long? {
        val text = String(item.bytes, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        return try {
            LocalDateTime.parse(text.take(GpsuChars), GpsuFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    private const val Gps5Fields = 5
    private const val Gps9Fields = 9
    private const val Gps5Types = "lllll"
    private const val Gps9DefaultTypes = "lllllllSS"
    private const val Gps5UnknownFix = UnknownFix
    private const val GpsuChars = 16
    private const val MicrosPerMilli = 1000L
    private const val MillisPerSecond = 1000.0
    private val Gps9Epoch: LocalDate = LocalDate.of(2000, 1, 1)
    private val GpsuFormat = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS")
}
