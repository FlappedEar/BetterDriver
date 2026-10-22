package com.flappedear.betterdriver.video.gpmf

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Builds GPMF payloads and a minimal MP4 with a GoPro telemetry track, as described in gpmf-parser's README. */
object FakeGoProFile {

    fun klv(key: String, type: Char, structSize: Int, repeat: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        d.writeBytes(key)
        d.writeByte(type.code)
        d.writeByte(structSize)
        d.writeShort(repeat)
        d.write(data)
        repeat((4 - data.size % 4) % 4) { d.writeByte(0) }
        return out.toByteArray()
    }

    fun nested(key: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        // Nested items use structure size 1 and repeat = byte length (the parser only needs size × repeat).
        return klv(key, '\u0000', 1, body.size, body)
    }

    private fun ints(vararg v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { v.forEach(::writeInt) }
        return out.toByteArray()
    }

    /** One GPS5 payload: [speeds] in m/s at fixed lat/lon, GPSU = [utcMillis]. HERO8-style. */
    fun gps5Payload(utcMillis: Long, speeds: List<Double>, fix: Int = 3): ByteArray {
        val gpsu = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS").withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(utcMillis))
        val data = speeds.flatMap { s ->
            listOf(52_200_0000, 19_100_0000, 100_000, (s * 1000).toInt(), (s * 1000).toInt())
        }.toIntArray()
        return nested(
            "DEVC",
            klv("DVNM", 'c', 1, 6, "Camera".toByteArray()),
            nested(
                "STRM",
                klv("STNM", 'c', 1, 3, "GPS".toByteArray()),
                klv("GPSF", 'L', 4, 1, ints(fix)),
                klv("GPSU", 'U', 16, 1, gpsu.toByteArray()),
                klv("SCAL", 'l', 4, 5, ints(10_000_000, 10_000_000, 1000, 1000, 100)),
                klv("GPS5", 'l', 20, speeds.size, ints(*data)),
            ),
        )
    }

    /** One GPS9 payload with per-sample UTC, HERO11-style. */
    fun gps9Payload(firstUtcMillis: Long, stepMillis: Long, speeds: List<Double>): ByteArray {
        val epoch2000 = 946_684_800_000L
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)
        speeds.forEachIndexed { i, s ->
            val utc = firstUtcMillis + i * stepMillis
            val days = (utc - epoch2000) / 86_400_000L
            val msOfDay = (utc - epoch2000) % 86_400_000L
            d.writeInt(522_000_000); d.writeInt(191_000_000); d.writeInt(100_000)
            d.writeInt((s * 1000).toInt()); d.writeInt((s * 1000).toInt())
            d.writeInt(days.toInt()); d.writeInt(msOfDay.toInt())
            d.writeShort(150); d.writeShort(3)
        }
        return nested(
            "DEVC",
            nested(
                "STRM",
                klv("STNM", 'c', 1, 3, "GPS".toByteArray()),
                klv("SCAL", 'l', 4, 9, ints(10_000_000, 10_000_000, 1000, 1000, 100, 1, 1000, 100, 1)),
                klv("TYPE", 'c', 1, 9, "lllllllSS".toByteArray()),
                klv("GPS9", '?', 32, speeds.size, out.toByteArray()),
            ),
        )
    }

    /**
     * Writes an MP4 whose only track is GPMF, one payload per second (timescale 1000). With
     * [fixedSampleSize], `stsz` gives one size for all payloads (they must be equal) and no table.
     */
    fun writeMp4(
        file: File,
        payloads: List<ByteArray>,
        creationSecondsSince1904: Long = 3_800_000_000L,
        fixedSampleSize: Boolean = false,
    ) {
        val header = box("ftyp", "mp41".toByteArray() + ints(0) + "mp41".toByteArray())
        val mdatBody = payloads.fold(ByteArray(0)) { acc, b -> acc + b }
        val mdatStart = header.size + 8
        val offsets = payloads.runningFold(mdatStart.toLong()) { acc, p -> acc + p.size }.dropLast(1)
        val stbl = box(
            "stbl",
            fullBox("stsd", ints(1) + box("gpmd", ByteArray(8))),
            fullBox("stts", ints(1, payloads.size, 1000)),
            fullBox("stsc", ints(1, 1, 1, 1)),
            if (fixedSampleSize) {
                require(payloads.map { it.size }.distinct().size <= 1) { "fixed size needs equal payloads" }
                fullBox("stsz", ints(payloads.firstOrNull()?.size ?: 0, payloads.size))
            } else {
                fullBox("stsz", ints(0, payloads.size, *payloads.map { it.size }.toIntArray()))
            },
            fullBox("stco", ints(payloads.size, *offsets.map { it.toInt() }.toIntArray())),
        )
        val trak = box(
            "trak",
            box(
                "mdia",
                fullBox("mdhd", ints(0, 0, 1000, payloads.size * 1000, 0)),
                fullBox("hdlr", ints(0) + "meta".toByteArray() + ByteArray(12) + "GoPro MET\u0000".toByteArray()),
                box("minf", stbl),
            ),
        )
        val mvhd = fullBox("mvhd", ints(creationSecondsSince1904.toInt(), creationSecondsSince1904.toInt(), 1000,
            payloads.size * 1000) + ByteArray(80))
        file.writeBytes(header + box("mdat", mdatBody) + box("moov", mvhd, trak))
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return ints(body.size + 8) + type.toByteArray() + body
    }

    private fun fullBox(type: String, body: ByteArray) = box(type, ints(0) + body)
}
