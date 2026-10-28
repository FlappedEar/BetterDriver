@file:Suppress("MagicNumber") // Field offsets of the ISO base media box layouts.

package com.flappedear.betterdriver.video.mp4

import com.flappedear.betterdriver.video.IoProblem

/**
 * Thrown when a file is not an MP4 (or GPMF payload) this reader understands. The parser contract
 * (audit A04): a malformed input ends with this exception and nothing else, so a caller that
 * handles [IoProblem] (IOException on the JVM) handles every damaged file; no other exception type
 * leaves [Mp4Parser] or the GPMF reader for a bad file.
 */
class Mp4FormatException(message: String, cause: Throwable? = null) : IoProblem(message, cause)

/**
 * Runs [block] on input that may be damaged: an out-of-range read the explicit checks missed
 * ends as [Mp4FormatException] rather than reaching the caller as a runtime exception.
 */
@Suppress("TooGenericExceptionCaught") // The safety net of the contract above.
internal inline fun <T> parsing(what: String, block: () -> T): T = try {
    block()
} catch (e: CutShort) {
    throw Mp4FormatException("$what is cut short", e)
} catch (e: ArithmeticException) {
    throw Mp4FormatException("$what has out-of-range numbers", e)
} catch (e: RuntimeException) {
    // IllegalArgumentException, IndexOutOfBoundsException and, on the JVM, NegativeArraySizeException.
    throw Mp4FormatException("$what is damaged: ${e.message}", e)
}

/**
 * A file the readers can read: its [length], and [read] of bytes at any offset. A read past the end
 * throws [IoProblem]. Android reads a java.io.File, iOS a file through Foundation.
 */
interface Mp4Source : AutoCloseable {
    val length: Long

    fun read(offset: Long, size: Int): ByteArray
}

/** One sample (for GPMF: one payload) of a track: where it is in the file and when it plays. */
data class Mp4Sample(val offset: Long, val size: Int, val startUs: Long, val durationUs: Long)

/**
 * A track's handler type, sample format and samples. [samples] are filled for the GoPro telemetry
 * track only ([Mp4Info.gpmfTrack]): nothing reads another track's samples, and a video track's
 * table is large (audit A05/A06).
 */
data class Mp4Track(val handler: String, val format: String, val timescale: Long, val samples: List<Mp4Sample>)

/**
 * What the reader takes from an MP4: [creationTimeMillis] from `mvhd` as stored, on the camera's own clock;
 * GoPros write local time there, not UTC), the movie duration and every track's sample table.
 */
data class Mp4Info(val creationTimeMillis: Long?, val durationUs: Long, val tracks: List<Mp4Track>) {
    /** The GoPro telemetry track: handler `meta`, sample format `gpmd`. */
    val gpmfTrack: Mp4Track? get() = tracks.firstOrNull { it.format == TelemetryFormat }
}

private const val TelemetryFormat = "gpmd"

/**
 * A small ISO base media file reader: it reads only `moov` (the index), never the media data,
 * so it is fast on multi-gigabyte GoPro files. Structure as in ISO/IEC 14496-12; GoPro stores GPMF
 * in a `meta` track with sample format `gpmd` (https://github.com/gopro/gpmf-parser).
 *
 * Every field is checked against its box before it is read, and every count and size from the file
 * against what the box and the file can hold before work or memory depends on it (audit A04/A05).
 */
@Suppress("TooManyFunctions") // One small function per box.
object Mp4Parser {
    private const val MaxMoovBytes = 64L * 1024 * 1024
    private const val SecondsFrom1904To1970 = 2_082_844_800L
    private const val MicrosPerSecond = 1_000_000L

    /** Telemetry samples in one file: GoPro writes one a second, so this is over 11 days. */
    internal const val MaxTelemetrySamples = 1_000_000

    /** One GPMF payload: a second of every sensor is a few KB; anything this large is damage. */
    internal const val MaxTelemetrySampleBytes = 4 * 1024 * 1024

    /** The index of the file [name] that [source] reads. */
    fun read(source: Mp4Source, name: String): Mp4Info {
        val moov = findMoov(source) ?: throw Mp4FormatException("no moov box")
        return parsing("$name: moov") { parseMoov(ByteReader(moov), source.length) }
    }

    /** Reads [sample]'s bytes; a sample that is too large or lies outside the file is refused before any allocation. */
    fun readSample(source: Mp4Source, sample: Mp4Sample): ByteArray {
        checkSample(sample.offset, sample.size.toLong(), source.length)
        return source.read(sample.offset, sample.size)
    }

    /**
     * Whether [source] is a finished MP4: it has a `moov` box. A recording cut off by a crash (the camera
     * process died, the phone switched off) has only its media data and cannot be played. Reads box
     * headers only, so it is fast on large files.
     */
    fun isComplete(source: Mp4Source): Boolean = try {
        moovBody(source) != null
    } catch (_: IoProblem) {
        false
    }

    private fun findMoov(source: Mp4Source): ByteArray? = moovBody(source)?.let { (start, size) ->
        source.read(start, size)
    }

    /** Where the `moov` box's body starts and its size, or null when there is none. */
    @Suppress("ReturnCount", "ThrowsCount") // One reason per way a box header can be bad.
    private fun moovBody(source: Mp4Source): Pair<Long, Int>? {
        var pos = 0L
        val length = source.length
        while (pos + 8 <= length) {
            val head = ByteReader(source.read(pos, 8))
            var size = head.uint()
            val type = fourCc(head)
            var header = 8L
            if (size == 1L) {
                if (pos + 16 > length) throw Mp4FormatException("$type box header cut short at $pos")
                size = ByteReader(source.read(pos + 8, 8)).long
                header = 16L
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < header || size > length - pos) {
                if (type == "moov") throw Mp4FormatException("moov too large or cut off")
                if (size < header) throw Mp4FormatException("bad box size $size at $pos")
                return null // The last box runs past the end: a file cut off while recording.
            }
            if (type == "moov") {
                val bodySize = size - header
                if (bodySize > MaxMoovBytes) throw Mp4FormatException("moov too large or cut off")
                return (pos + header) to bodySize.toInt()
            }
            pos += size
        }
        return null
    }

    private fun parseMoov(buf: ByteReader, fileLength: Long): Mp4Info {
        var creation: Long? = null
        var durationUs = 0L
        val tracks = mutableListOf<Mp4Track>()
        for (box in children(buf)) {
            when (box.type) {
                "mvhd" -> {
                    val b = box.body
                    val version = fullBoxVersion(b, "mvhd")
                    // Creation, modification, timescale, duration: 4+4+4+4 (version 0) or 8+8+4+8 bytes.
                    b.need(if (version == 1) 28 else 16, "mvhd")
                    val created: Long
                    val timescale: Long
                    val duration: Long
                    if (version == 1) {
                        created = b.long
                        b.long
                        timescale = b.uint()
                        duration = b.long
                    } else {
                        created = b.uint()
                        b.uint()
                        timescale = b.uint()
                        duration = b.uint()
                    }
                    if (created > 0) creation = (created - SecondsFrom1904To1970) * 1000
                    if (timescale > 0 && duration >= 0) durationUs = scale(duration, timescale)
                }
                "trak" -> parseTrak(box.body, fileLength)?.let(tracks::add)
            }
        }
        return Mp4Info(creation, durationUs, tracks)
    }

    private fun parseTrak(buf: ByteReader, fileLength: Long): Mp4Track? {
        val mdia = children(buf).firstOrNull { it.type == "mdia" } ?: return null
        var timescale = 0L
        var handler = ""
        var stbl: ByteReader? = null
        for (box in children(mdia.body)) {
            when (box.type) {
                "mdhd" -> {
                    val b = box.body
                    val version = fullBoxVersion(b, "mdhd")
                    // Creation and modification (4+4 or 8+8 bytes), then the timescale.
                    val times = if (version == 1) 16 else 8
                    b.need(times + 4, "mdhd")
                    b.position(b.position() + times)
                    timescale = b.uint()
                }
                "hdlr" -> {
                    val b = box.body
                    // Version and flags, pre_defined, then the handler type.
                    b.need(12, "hdlr")
                    b.position(b.position() + 8)
                    handler = fourCc(b)
                }
                "minf" -> stbl = children(box.body).firstOrNull { it.type == "stbl" }?.body
            }
        }
        if (stbl != null && timescale <= 0) throw Mp4FormatException("track without timescale")
        return stbl?.let { parseStbl(it, handler, timescale, fileLength) }
    }

    private fun parseStbl(buf: ByteReader, handler: String, timescale: Long, fileLength: Long): Mp4Track {
        val boxes = children(buf).associateBy { it.type }
        val format = boxes["stsd"]?.body?.let(::sampleFormat).orEmpty()
        if (format != TelemetryFormat) return Mp4Track(handler, format, timescale, emptyList())
        return Mp4Track(handler, format, timescale, SampleTables.read(boxes, timescale, fileLength))
    }

    /** The first sample description's format, or "" when the box lists none. */
    private fun sampleFormat(b: ByteReader): String {
        b.need(8, "stsd")
        b.int
        if (b.uint() == 0L) return ""
        b.need(8, "stsd entry")
        b.int
        return fourCc(b)
    }

    internal fun checkSample(offset: Long, size: Long, fileLength: Long) {
        if (size !in 0..MaxTelemetrySampleBytes.toLong()) throw Mp4FormatException("telemetry sample of $size bytes")
        if (offset < 0 || offset > fileLength - size) {
            throw Mp4FormatException("telemetry sample at $offset+$size outside the file ($fileLength bytes)")
        }
    }

    internal fun scale(ticks: Long, timescale: Long): Long = addExact(
        multiplyExact(ticks / timescale, MicrosPerSecond),
        ticks % timescale * MicrosPerSecond / timescale,
    )
}

/**
 * The telemetry track's sample tables (stts, stsc, stsz, stco/co64) expanded into samples. Every
 * count is checked against the bytes its box holds, and the expansion never does more work than
 * the samples the track declares, which are at most [Mp4Parser.MaxTelemetrySamples] (audit A05).
 * `stsz` with one size for all samples has no per-sample entries (audit A06; ISO/IEC 14496-12 8.7.3).
 */
private object SampleTables {
    @Suppress("ThrowsCount") // One reason per way a table can be bad.
    fun read(boxes: Map<String, Box>, timescale: Long, fileLength: Long): List<Mp4Sample> {
        val sizes = boxes["stsz"]?.body?.let(::sizes) ?: return emptyList()
        val chunkOffsets = boxes["stco"]?.body?.let { table(it, "stco", 4) { b -> b.uint() } }
            ?: boxes["co64"]?.body?.let { table(it, "co64", 8) { b -> b.long } }
            ?: throw Mp4FormatException("telemetry track without chunk offsets")
        val chunkRuns = boxes["stsc"]?.body
            ?.let { table(it, "stsc", 12) { b -> Triple(b.uint(), b.uint(), b.uint()) } }
            ?: throw Mp4FormatException("telemetry track without stsc")
        val deltas = boxes["stts"]?.body?.let { table(it, "stts", 8) { b -> b.uint() to b.uint() } }
            ?: throw Mp4FormatException("telemetry track without stts")
        // ISO/IEC 14496-12: the time table covers exactly the samples; a mismatch is a damaged index.
        val timed = deltas.sumOf { it.first }
        if (timed != sizes.count.toLong()) {
            throw Mp4FormatException("stts times $timed samples, stsz has ${sizes.count}")
        }
        val offsets = offsets(sizes, chunkRuns, chunkOffsets)
        val result = ArrayList<Mp4Sample>(offsets.size)
        var ticks = 0L
        for ((n, delta) in deltas) {
            // At most the samples still without a time, however large the run says it is.
            val take = minOf(n, (offsets.size - result.size).toLong()).toInt()
            repeat(take) {
                val i = result.size
                Mp4Parser.checkSample(offsets[i], sizes.size(i).toLong(), fileLength)
                result += Mp4Sample(
                    offsets[i],
                    sizes.size(i),
                    Mp4Parser.scale(ticks, timescale),
                    Mp4Parser.scale(delta, timescale),
                )
                ticks = addExact(ticks, delta)
            }
            if (result.size == offsets.size) break
        }
        return result
    }

    /** One size for every sample, or one entry per sample. */
    private class Sizes(val count: Int, val fixed: Int, val each: IntArray?) {
        fun size(i: Int) = each?.get(i) ?: fixed
    }

    @Suppress("ThrowsCount") // One reason per way the table can be bad.
    private fun sizes(b: ByteReader): Sizes {
        b.need(12, "stsz")
        b.int
        val fixed = b.uint()
        val count = b.uint()
        if (count > Mp4Parser.MaxTelemetrySamples) throw Mp4FormatException("stsz lists $count telemetry samples")
        if (fixed != 0L) {
            if (fixed > Mp4Parser.MaxTelemetrySampleBytes) throw Mp4FormatException("telemetry sample of $fixed bytes")
            return Sizes(count.toInt(), fixed.toInt(), null)
        }
        if (count * 4 > b.remaining()) throw Mp4FormatException("stsz table of $count larger than its box")
        val each = IntArray(count.toInt()) {
            val size = b.uint()
            if (size > Mp4Parser.MaxTelemetrySampleBytes) throw Mp4FormatException("telemetry sample of $size bytes")
            size.toInt()
        }
        return Sizes(count.toInt(), 0, each)
    }

    /** Offsets chunk by chunk, each chunk holding the sample count of the stsc run it falls in. */
    private fun offsets(sizes: Sizes, chunkRuns: List<Triple<Long, Long, Long>>, chunkOffsets: List<Long>): LongArray {
        val offsets = LongArray(sizes.count)
        var sample = 0
        var run = -1
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val chunkNumber = chunkIndex + 1L
            while (run + 1 < chunkRuns.size && chunkRuns[run + 1].first <= chunkNumber) run++
            if (run < 0 || sample == sizes.count) break
            // At most the samples left, however many the run says a chunk holds.
            val take = minOf(chunkRuns[run].second, (sizes.count - sample).toLong()).toInt()
            var offset = chunkOffset
            repeat(take) {
                offsets[sample] = offset
                offset = addExact(offset, sizes.size(sample).toLong())
                sample++
            }
        }
        if (sample != sizes.count) throw Mp4FormatException("chunks hold $sample of ${sizes.count} samples")
        return offsets
    }

    /** A table of `entry count` entries of [entryBytes] each, checked against its box before it is read. */
    private fun <T> table(b: ByteReader, what: String, entryBytes: Int, entry: (ByteReader) -> T): List<T> {
        b.need(8, what)
        b.int
        val n = b.uint()
        if (n * entryBytes > b.remaining()) throw Mp4FormatException("$what table of $n larger than its box")
        // A telemetry track needs no more entries than samples; more is a damaged table.
        if (n > Mp4Parser.MaxTelemetrySamples) throw Mp4FormatException("$what table of $n entries")
        return List(n.toInt()) { entry(b) }
    }
}

private class Box(val type: String, val body: ByteReader)

private fun children(buf: ByteReader): List<Box> {
    val boxes = mutableListOf<Box>()
    val b = buf.duplicate()
    while (b.remaining() >= 8) {
        val start = b.position()
        var size = b.uint()
        val type = fourCc(b)
        if (size == 1L) {
            b.need(8, "$type box header")
            size = b.long
        }
        if (size == 0L) size = (b.limit() - start).toLong()
        val headerSize = b.position() - start
        if (size < headerSize || size > b.limit() - start) throw Mp4FormatException("bad $type box")
        val body = b.duplicate().apply {
            position(start + headerSize)
            limit((start + size).toInt())
        }.slice()
        boxes += Box(type, body)
        b.position((start + size).toInt())
    }
    return boxes
}

/** Reads a full box's version and flags; versions 0 and 1 are the ones ISO/IEC 14496-12 defines. */
private fun fullBoxVersion(b: ByteReader, what: String): Int {
    b.need(4, what)
    val version = b.get().toInt() and 0xFF
    b.position(b.position() + 3)
    if (version > 1) throw Mp4FormatException("$what version $version")
    return version
}

/** Fails with [Mp4FormatException] unless [bytes] are left in this box. */
internal fun ByteReader.need(bytes: Int, what: String) {
    if (remaining() < bytes) throw Mp4FormatException("$what is cut short (needs $bytes bytes, has ${remaining()})")
}

private fun fourCc(b: ByteReader): String = ByteArray(4).also { b.get(it) }.ascii()

private fun ByteReader.uint(): Long = int.toLong() and 0xFFFFFFFFL

/** [a] + [b], or [ArithmeticException] when it does not fit a Long, as Math.addExact. */
internal fun addExact(a: Long, b: Long): Long {
    val r = a + b
    if ((a xor r) and (b xor r) < 0) throw ArithmeticException("long overflow")
    return r
}

/** [a] × [b], or [ArithmeticException] when it does not fit a Long, as Math.multiplyExact. */
internal fun multiplyExact(a: Long, b: Long): Long {
    val r = a * b
    val minTimesMinusOne = a == -1L && b == Long.MIN_VALUE
    if (a != 0L && (minTimesMinusOne || r / a != b)) throw ArithmeticException("long overflow")
    return r
}
