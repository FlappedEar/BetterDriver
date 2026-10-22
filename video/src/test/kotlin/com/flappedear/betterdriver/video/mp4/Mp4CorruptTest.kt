package com.flappedear.betterdriver.video.mp4

import com.flappedear.betterdriver.video.gpmf.FakeGoProFile
import com.flappedear.betterdriver.video.gpmf.GpmfGps
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Damaged and unusual MP4 files (audit 2026-10-22, A04-A06): every one ends with [Mp4FormatException]
 * or reads correctly, in bounded time and without allocations sized by the damaged numbers.
 */
class Mp4CorruptTest {
    private val dir = Files.createTempDirectory("mp4corrupt").toFile().apply { deleteOnExit() }

    private fun ints(vararg v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { v.forEach(::writeInt) }
        return out.toByteArray()
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return ints(body.size + 8) + type.toByteArray() + body
    }

    private fun fullBox(type: String, body: ByteArray) = box(type, ints(0) + body)

    private fun file(name: String, bytes: ByteArray): File = File(dir, name).apply { writeBytes(bytes) }

    private fun hex(s: String): ByteArray =
        s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Reading [file] either works or ends with the format error, quickly. */
    private fun readsOrRefuses(file: File) = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        try {
            Mp4Reader.read(file)
            GpmfGps.read(file)
        } catch (_: Mp4FormatException) {
            // The contract: a damaged file is this exception, nothing else.
        }
    }

    private fun refuses(file: File) = assertTimeoutPreemptively(Duration.ofSeconds(5)) {
        assertFailsWith<Mp4FormatException> { GpmfGps.read(file) }
    }

    /** A moov holding one GPMF track built from these sample table boxes; payload bytes follow in mdat. */
    private fun telemetryFile(name: String, payload: ByteArray, vararg tables: ByteArray): File {
        val stbl = box("stbl", fullBox("stsd", ints(1) + box("gpmd", ByteArray(8))), *tables)
        val trak = box(
            "trak",
            box(
                "mdia",
                fullBox("mdhd", ints(0, 0, 1000, 1000, 0)),
                fullBox("hdlr", ints(0) + "meta".toByteArray() + ByteArray(12)),
                box("minf", stbl),
            ),
        )
        return file(name, box("mdat", payload) + box("moov", trak))
    }

    @Test
    fun `audit A04 input - moov with an empty mvhd is a format error`() {
        refuses(file("a04.mp4", hex("00 00 00 10 6d 6f 6f 76 00 00 00 08 6d 76 68 64")))
    }

    @Test
    fun `mvhd mdhd and hdlr cut at every field are format errors`() {
        val mvhd0 = ints(0, 1, 2, 1000, 5000) // version/flags, created, modified, timescale, duration
        val mvhd1 = ints(0x01000000) + ByteArray(8) + ByteArray(8) + ints(1000) + ByteArray(8)
        for (body in listOf(mvhd0, mvhd1)) {
            for (cut in 0 until body.size) {
                refuses(file("mvhd$cut.mp4", box("moov", box("mvhd", body.copyOf(cut)))))
            }
            Mp4Reader.read(file("mvhd-whole.mp4", box("moov", box("mvhd", body))))
        }
        val mdhd = ints(0, 1, 2, 1000)
        val hdlr = ints(0, 0) + "meta".toByteArray()
        for (cut in 0 until mdhd.size) {
            val trak = box("trak", box("mdia", box("mdhd", mdhd.copyOf(cut)), box("minf", box("stbl"))))
            refuses(file("mdhd$cut.mp4", box("moov", trak)))
        }
        for (cut in 0 until hdlr.size) {
            val trak = box("trak", box("mdia", box("hdlr", hdlr.copyOf(cut))))
            refuses(file("hdlr$cut.mp4", box("moov", trak)))
        }
    }

    @Test
    fun `an extended box size cut short or out of range is a format error`() {
        // Size 1 means a 64-bit size follows; here it is cut off, then too large, then smaller than its header.
        refuses(file("ext-cut.mp4", box("moov", ints(1) + "trak".toByteArray() + ints(0))))
        refuses(file("ext-big.mp4", box("moov", ints(1) + "trak".toByteArray() + ints(0x7FFFFFFF, -1))))
        refuses(file("ext-small.mp4", box("moov", ints(1) + "trak".toByteArray() + ints(0, 4))))
    }

    @Test
    fun `unknown full box version is a format error`() {
        refuses(file("mvhd-v2.mp4", box("moov", box("mvhd", ints(0x02000000) + ByteArray(40)))))
    }

    @Test
    fun `one sample with a run of 2^31 - 1 samples per chunk reads in bounded time`() {
        val payload = ByteArray(16)
        val file = telemetryFile(
            "a05-stsc.mp4",
            payload,
            fullBox("stts", ints(1, 1, 1000)),
            fullBox("stsc", ints(1, 1, Int.MAX_VALUE, 1)),
            fullBox("stsz", ints(0, 1, payload.size)),
            fullBox("stco", ints(1, 8)),
        )
        val samples = assertTimeoutPreemptively(Duration.ofSeconds(5)) { Mp4Reader.read(file).gpmfTrack!!.samples }
        assertEquals(listOf(Mp4Sample(8, 16, 0, 1_000_000)), samples)
    }

    @Test
    fun `a time run of 2^31 - 1 for one sample is refused in bounded time`() {
        val payload = ByteArray(16)
        refuses(
            telemetryFile(
                "a05-stts.mp4",
                payload,
                fullBox("stts", ints(1, Int.MAX_VALUE, 1000)),
                fullBox("stsc", ints(1, 1, 1, 1)),
                fullBox("stsz", ints(0, 1, payload.size)),
                fullBox("stco", ints(1, 8)),
            ),
        )
    }

    @Test
    fun `a huge sample size or an offset past the end is refused before reading it`() {
        val payload = ByteArray(16)
        fun withSizeAndOffset(name: String, size: Int, offset: Int) = telemetryFile(
            name,
            payload,
            fullBox("stts", ints(1, 1, 1000)),
            fullBox("stsc", ints(1, 1, 1, 1)),
            fullBox("stsz", ints(0, 1, size)),
            fullBox("stco", ints(1, offset)),
        )
        refuses(withSizeAndOffset("a05-size.mp4", -1, 8)) // 4 GiB - 1 as unsigned
        refuses(withSizeAndOffset("a05-size2.mp4", 0x7FFFFFF0, 8))
        refuses(withSizeAndOffset("a05-offset.mp4", 16, 0x7FFFFFF0))
        refuses(withSizeAndOffset("a05-fixed.mp4", 0, 8).also { f ->
            // Same file with a fixed size that is too large for a telemetry payload.
            f.writeBytes(f.readBytes().let { b -> replaceStsz(b, ints(0x7FFFFFF0, 1)) })
        })
        // readSample checks its argument too, before allocating.
        val ok = withSizeAndOffset("a05-ok.mp4", 16, 8)
        assertFailsWith<Mp4FormatException> { Mp4Reader.readSample(ok, Mp4Sample(8, Int.MAX_VALUE, 0, 0)) }
        assertFailsWith<Mp4FormatException> { Mp4Reader.readSample(ok, Mp4Sample(1L shl 40, 16, 0, 0)) }
        assertFailsWith<Mp4FormatException> { Mp4Reader.readSample(ok, Mp4Sample(-1, 16, 0, 0)) }
    }

    /** [bytes] with the body of its stsz box (after version/flags) replaced by [body] of the same length. */
    private fun replaceStsz(bytes: ByteArray, body: ByteArray): ByteArray {
        val at = String(bytes, Charsets.ISO_8859_1).indexOf("stsz") + 8
        return bytes.copyOf().also { body.copyInto(it, at) }
    }

    @Test
    fun `table counts larger than their box are refused`() {
        val payload = ByteArray(16)
        refuses(
            telemetryFile(
                "count.mp4",
                payload,
                fullBox("stts", ints(Int.MAX_VALUE)),
                fullBox("stsc", ints(1, 1, 1, 1)),
                fullBox("stsz", ints(0, 1, 16)),
                fullBox("stco", ints(1, 8)),
            ),
        )
        // Variable sizes: count says 3, the box holds 1.
        refuses(
            telemetryFile(
                "stsz-cut.mp4",
                payload,
                fullBox("stts", ints(1, 3, 1000)),
                fullBox("stsc", ints(1, 1, 3, 1)),
                fullBox("stsz", ints(0, 3, 16)),
                fullBox("stco", ints(1, 8)),
            ),
        )
    }

    @Test
    fun `audit A06 - fixed and variable stsz give the same samples`() {
        val payloads = List(4) { s -> FakeGoProFile.gps5Payload(1_700_000_000_000L + s * 1000, List(10) { 12.0 }) }
        val variable = File(dir, "var.mp4").also { FakeGoProFile.writeMp4(it, payloads) }
        val fixed = File(dir, "fixed.mp4").also { FakeGoProFile.writeMp4(it, payloads, fixedSampleSize = true) }
        val a = Mp4Reader.read(variable).gpmfTrack!!.samples
        val b = Mp4Reader.read(fixed).gpmfTrack!!.samples
        assertEquals(4, a.size)
        // The fixed-size file has 16 bytes less of stsz table; mdat comes first, so offsets match.
        assertEquals(a, b)
        assertEquals(GpmfGps.read(variable), GpmfGps.read(fixed))
    }

    @Test
    fun `every single byte damage of a real-shaped file reads or refuses`() {
        val payloads = List(3) { s -> FakeGoProFile.gps9Payload(1_700_000_000_000L + s * 1000, 100, List(10) { 20.0 }) }
        val good = File(dir, "good.mp4").also { FakeGoProFile.writeMp4(it, payloads) }.readBytes()
        val moov = String(good, Charsets.ISO_8859_1).indexOf("moov") - 4
        val damaged = File(dir, "damaged.mp4")
        for (at in moov until good.size) {
            for (value in listOf(0x00, 0xFF, 0x7F, 0x01)) {
                damaged.writeBytes(good.copyOf().also { it[at] = value.toByte() })
                readsOrRefuses(damaged)
            }
        }
    }

    @Test
    fun `the format error is an IOException, so importers that handle IOException handle it`() {
        val e: IOException = assertFailsWith<Mp4FormatException> {
            Mp4Reader.read(file("cut.mp4", hex("00 00 00 10 6d 6f 6f 76 00 00 00 08 6d 76 68 64")))
        }
        assertTrue(e.message!!.isNotEmpty())
    }
}
