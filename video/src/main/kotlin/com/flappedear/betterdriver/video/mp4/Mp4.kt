@file:Suppress("MagicNumber") // Field offsets of the ISO base media box layouts.

package com.flappedear.betterdriver.video.mp4

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Thrown when a file is not an MP4 this reader understands. */
class Mp4FormatException(message: String) : IOException(message)

/** One sample (for GPMF: one payload) of a track: where it is in the file and when it plays. */
data class Mp4Sample(val offset: Long, val size: Int, val startUs: Long, val durationUs: Long)

/** A track's handler type, sample format and samples. */
data class Mp4Track(val handler: String, val format: String, val timescale: Long, val samples: List<Mp4Sample>)

/**
 * What the reader takes from an MP4: [creationTimeMillis] from `mvhd` as stored, on the camera's own clock;
 * GoPros write local time there, not UTC), the movie duration and every track's sample table.
 */
data class Mp4Info(val creationTimeMillis: Long?, val durationUs: Long, val tracks: List<Mp4Track>) {
    /** The GoPro telemetry track: handler `meta`, sample format `gpmd`. */
    val gpmfTrack: Mp4Track? get() = tracks.firstOrNull { it.format == "gpmd" }
}

/**
 * A small ISO base media file reader: it reads only `moov` (the index), never the media data,
 * so it is fast on multi-gigabyte GoPro files. Structure as in ISO/IEC 14496-12; GoPro stores GPMF
 * in a `meta` track with sample format `gpmd` (https://github.com/gopro/gpmf-parser).
 */
object Mp4Reader {
    private const val MaxMoovBytes = 64L * 1024 * 1024
    private const val SecondsFrom1904To1970 = 2_082_844_800L
    private const val MicrosPerSecond = 1_000_000L

    fun read(file: File): Mp4Info = RandomAccessFile(file, "r").use { raf ->
        val moov = findMoov(raf) ?: throw Mp4FormatException("no moov box")
        parseMoov(ByteBuffer.wrap(moov))
    }

    /** Reads [sample]'s bytes. */
    fun readSample(file: File, sample: Mp4Sample): ByteArray = RandomAccessFile(file, "r").use { raf ->
        ByteArray(sample.size).also {
            raf.seek(sample.offset)
            raf.readFully(it)
        }
    }

    private fun findMoov(raf: RandomAccessFile): ByteArray? {
        var pos = 0L
        val length = raf.length()
        while (pos + 8 <= length) {
            raf.seek(pos)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
            var header = 8L
            if (size == 1L) {
                size = raf.readLong()
                header = 16L
            } else if (size == 0L) {
                size = length - pos
            }
            if (size < header) throw Mp4FormatException("bad box size $size at $pos")
            if (type == "moov") {
                val bodySize = size - header
                if (bodySize > MaxMoovBytes || pos + size > length) {
                    throw Mp4FormatException("moov too large or cut off")
                }
                return ByteArray(bodySize.toInt()).also { raf.readFully(it) }
            }
            pos += size
        }
        return null
    }

    private fun parseMoov(buf: ByteBuffer): Mp4Info {
        var creation: Long? = null
        var durationUs = 0L
        val tracks = mutableListOf<Mp4Track>()
        for (box in children(buf)) {
            when (box.type) {
                "mvhd" -> {
                    val b = box.body
                    val version = b.get().toInt()
                    b.position(b.position() + 3)
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
                    if (timescale > 0) durationUs = scale(duration, timescale)
                }
                "trak" -> parseTrak(box.body)?.let(tracks::add)
            }
        }
        return Mp4Info(creation, durationUs, tracks)
    }

    private fun parseTrak(buf: ByteBuffer): Mp4Track? {
        val mdia = children(buf).firstOrNull { it.type == "mdia" } ?: return null
        var timescale = 0L
        var handler = ""
        var stbl: ByteBuffer? = null
        for (box in children(mdia.body)) {
            when (box.type) {
                "mdhd" -> {
                    val b = box.body
                    val version = b.get().toInt()
                    b.position(b.position() + 3 + if (version == 1) 16 else 8)
                    timescale = b.uint()
                }
                "hdlr" -> {
                    val b = box.body
                    b.position(b.position() + 8)
                    handler = fourCc(b)
                }
                "minf" -> stbl = children(box.body).firstOrNull { it.type == "stbl" }?.body
            }
        }
        if (stbl != null && timescale <= 0) throw Mp4FormatException("track without timescale")
        return stbl?.let { parseStbl(it, handler, timescale) }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun parseStbl(buf: ByteBuffer, handler: String, timescale: Long): Mp4Track {
        var format = ""
        var deltas = listOf<Pair<Long, Long>>()
        var chunkRuns = listOf<Triple<Long, Long, Long>>()
        var sizes = listOf<Int>()
        var chunkOffsets = listOf<Long>()
        for (box in children(buf)) {
            val b = box.body
            when (box.type) {
                "stsd" -> {
                    b.int
                    if (b.uint() > 0) {
                        b.int
                        format = fourCc(b)
                    }
                }
                "stts" -> {
                    b.int
                    deltas = List(count(b)) { b.uint() to b.uint() }
                }
                "stsc" -> {
                    b.int
                    chunkRuns = List(count(b)) { Triple(b.uint(), b.uint(), b.uint()) }
                }
                "stsz" -> {
                    b.int
                    val fixed = b.uint()
                    val n = count(b)
                    sizes = if (fixed != 0L) List(n) { fixed.toInt() } else List(n) { b.uint().toInt() }
                }
                "stco" -> {
                    b.int
                    chunkOffsets = List(count(b)) { b.uint() }
                }
                "co64" -> {
                    b.int
                    chunkOffsets = List(count(b)) { b.long }
                }
            }
        }
        return Mp4Track(handler, format, timescale, samples(timescale, deltas, chunkRuns, sizes, chunkOffsets))
    }

    private fun samples(
        timescale: Long,
        deltas: List<Pair<Long, Long>>,
        chunkRuns: List<Triple<Long, Long, Long>>,
        sizes: List<Int>,
        chunkOffsets: List<Long>,
    ): List<Mp4Sample> {
        // Offsets: chunk by chunk, each with the sample count of the stsc run it falls in.
        val offsets = ArrayList<Long>(sizes.size)
        var sample = 0
        for ((chunkIndex, chunkOffset) in chunkOffsets.withIndex()) {
            val chunkNumber = chunkIndex + 1L
            val run = chunkRuns.lastOrNull { it.first <= chunkNumber } ?: break
            var offset = chunkOffset
            repeat(run.second.toInt()) {
                if (sample < sizes.size) {
                    offsets += offset
                    offset += sizes[sample]
                    sample++
                }
            }
        }
        // Times: expand the stts run-length table.
        val result = ArrayList<Mp4Sample>(offsets.size)
        var ticks = 0L
        var index = 0
        loop@ for ((n, delta) in deltas) {
            repeat(n.toInt()) {
                if (index >= offsets.size) return@repeat
                result += Mp4Sample(offsets[index], sizes[index], scale(ticks, timescale), scale(delta, timescale))
                ticks += delta
                index++
            }
            if (index >= offsets.size) break@loop
        }
        return result
    }

    private fun scale(ticks: Long, timescale: Long): Long =
        ticks / timescale * MicrosPerSecond + ticks % timescale * MicrosPerSecond / timescale
}

private class Box(val type: String, val body: ByteBuffer)

private fun children(buf: ByteBuffer): List<Box> {
    val boxes = mutableListOf<Box>()
    val b = buf.duplicate()
    while (b.remaining() >= 8) {
        val start = b.position()
        var size = b.uint()
        val type = fourCc(b)
        if (size == 1L) size = b.long
        if (size == 0L) size = (b.limit() - start).toLong()
        val headerSize = b.position() - start
        if (size < headerSize || start + size > b.limit()) throw Mp4FormatException("bad $type box")
        val body = b.duplicate().apply {
            position(start + headerSize)
            limit((start + size).toInt())
        }.slice()
        boxes += Box(type, body)
        b.position((start + size).toInt())
    }
    return boxes
}

private fun count(b: ByteBuffer): Int {
    val n = b.uint()
    if (n > b.remaining()) throw Mp4FormatException("table count $n larger than its box")
    return n.toInt()
}

private fun fourCc(b: ByteBuffer): String = ByteArray(4).also { b.get(it) }.toString(Charsets.US_ASCII)

private fun ByteBuffer.uint(): Long = int.toLong() and 0xFFFFFFFFL
