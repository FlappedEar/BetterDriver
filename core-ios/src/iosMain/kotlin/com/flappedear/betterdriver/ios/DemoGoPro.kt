@file:Suppress("MagicNumber") // GPMF field layouts and MP4 box fields.

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.log.logInfo
import com.flappedear.betterdriver.recorder.store.SessionStore

/**
 * A made-up GoPro file for a session, for the tests of adding GoPro files (FBBD-343): an MP4 whose
 * only track is GoPro GPMF telemetry, GPS9 at 10 Hz carrying the session's own speed, as a GoPro
 * riding along would have recorded it. Its video time 0 is session time [write]'s fromMicros, so
 * that is where a correct import places it. No picture: nothing plays it.
 */
object DemoGoPro {
    private const val SamplesPerSecond = 10

    /** GPS9's scale of latitude and longitude: 1e7 units a degree. */
    private const val Degrees = 10_000_000
    private const val Gps9Epoch = 946_684_800_000L
    private const val MillisPerDay = 86_400_000L
    private const val SecondsFrom1904To1970 = 2_082_844_800L

    /**
     * Writes [seconds] of telemetry from session time [fromMicros] of session [id] to [path], its GPS
     * clock [utcShiftMillis] off the session's (a file from another day when large). False when the
     * session has no GNSS speed to copy or the file cannot be written.
     */
    @Suppress("LongParameterList", "TooGenericExceptionCaught") // A test helper: it reports any failure as false.
    fun write(
        sessionsRoot: String,
        id: String,
        path: String,
        fromMicros: Long,
        seconds: Int,
        utcShiftMillis: Long = 0,
    ): Boolean = try {
        val data = SessionStore(sessionsRoot).read(id)
        val points = data.sampleSets.mapNotNull { set ->
            val speed = set.values.firstOrNull { it.channelId == GnssChannels.SPEED_KMH.id }?.value
            val utc = set.values.firstOrNull { it.channelId == GnssChannels.UTC_MS.id }?.value
            speed?.let { Triple(set.tMicros, it, utc ?: (data.info.startUtcMillis + set.tMicros / 1000.0)) }
        }.sortedBy { it.first }
        if (points.size < 2) {
            false
        } else {
            val payloads = List(seconds) { s ->
                val samples = List(SamplesPerSecond) { k ->
                    val t = fromMicros + s * 1_000_000L + k * (1_000_000L / SamplesPerSecond)
                    val (kmh, utc) = at(points, t)
                    Pair(kmh / 3.6, utc.toLong() + utcShiftMillis)
                }
                gps9Payload(samples)
            }
            val created = (data.info.startUtcMillis + fromMicros / 1000) / 1000 + SecondsFrom1904To1970
            writeBytesAtomically(path, mp4(payloads, created))
            true
        }
    } catch (e: Exception) {
        logInfo(GoProTag, "demo GoPro file for $id not written: ${e.message}")
        false
    }

    /** Speed and UTC at session time [t], interpolated between the samples around it. */
    private fun at(points: List<Triple<Long, Double, Double>>, t: Long): Pair<Double, Double> {
        val i = points.indexOfFirst { it.first >= t }.let { if (it <= 0) 1 else it }.coerceAtMost(points.size - 1)
        val a = points[i - 1]
        val b = points[i]
        val f = ((t - a.first).toDouble() / (b.first - a.first).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val utcAtT = a.third + (t - a.first) / 1000.0
        return Pair(a.second + f * (b.second - a.second), utcAtT)
    }

    private fun gps9Payload(samples: List<Pair<Double, Long>>): ByteArray {
        val body = BigEndian()
        for ((mps, utc) in samples) {
            val days = (utc - Gps9Epoch) / MillisPerDay
            val msOfDay = (utc - Gps9Epoch) % MillisPerDay
            val speed = (mps * 1000).toInt()
            body.ints(522_000_000, 191_000_000, 100_000, speed, speed, days.toInt(), msOfDay.toInt())
            body.shorts(150, 3)
        }
        return nested(
            "DEVC",
            nested(
                "STRM",
                klv("STNM", 'c', 1, 3, "GPS".encodeToByteArray()),
                klv("SCAL", 'l', 4, 9, BigEndian().ints(Degrees, Degrees, 1000, 1000, 100, 1, 1000, 100, 1).bytes()),
                klv("TYPE", 'c', 1, 9, "lllllllSS".encodeToByteArray()),
                klv("GPS9", '?', 32, samples.size, body.bytes()),
            ),
        )
    }

    private fun klv(key: String, type: Char, structSize: Int, repeat: Int, data: ByteArray): ByteArray {
        val head = BigEndian()
        head.raw(key.encodeToByteArray())
        head.raw(byteArrayOf(type.code.toByte(), structSize.toByte()))
        head.shorts(repeat)
        return head.bytes() + data + ByteArray((4 - data.size % 4) % 4)
    }

    private fun nested(key: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return klv(key, '\u0000', 1, body.size, body)
    }

    /** An MP4 whose only track is the GPMF payloads, one a second (timescale 1000). */
    private fun mp4(payloads: List<ByteArray>, created1904: Long): ByteArray {
        val header = box("ftyp", "mp41".encodeToByteArray() + int(0) + "mp41".encodeToByteArray())
        val mdatBody = payloads.fold(ByteArray(0)) { acc, b -> acc + b }
        val mdatStart = header.size + 8
        val offsets = payloads.runningFold(mdatStart) { acc, p -> acc + p.size }.dropLast(1)
        val stbl = box(
            "stbl",
            fullBox("stsd", int(1) + box("gpmd", ByteArray(8))),
            fullBox("stts", BigEndian().ints(1, payloads.size, 1000).bytes()),
            fullBox("stsc", BigEndian().ints(1, 1, 1, 1).bytes()),
            fullBox("stsz", BigEndian().ints(0, payloads.size).ints(payloads.map { it.size }).bytes()),
            fullBox("stco", BigEndian().ints(payloads.size).ints(offsets).bytes()),
        )
        val trak = box(
            "trak",
            box(
                "mdia",
                fullBox("mdhd", BigEndian().ints(0, 0, 1000, payloads.size * 1000, 0).bytes()),
                fullBox("hdlr", int(0) + ascii("meta") + ByteArray(12) + ascii("GoPro MET\u0000")),
                box("minf", stbl),
            ),
        )
        val times = BigEndian().ints(created1904.toInt(), created1904.toInt(), 1000, payloads.size * 1000).bytes()
        val mvhd = fullBox("mvhd", times + ByteArray(80))
        return header + box("mdat", mdatBody) + box("moov", mvhd, trak)
    }

    private fun box(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { acc, b -> acc + b }
        return int(body.size + 8) + type.encodeToByteArray() + body
    }

    private fun fullBox(type: String, body: ByteArray) = box(type, int(0) + body)

    private fun int(v: Int) = BigEndian().ints(v).bytes()

    private fun ascii(text: String) = text.encodeToByteArray()

    /** Big-endian bytes, as DataOutputStream writes them. */
    private class BigEndian {
        private val out = ArrayList<Byte>()

        fun ints(vararg values: Int): BigEndian = ints(values.asList())

        fun ints(values: List<Int>): BigEndian {
            for (v in values) for (shift in intArrayOf(24, 16, 8, 0)) out += (v ushr shift).toByte()
            return this
        }

        fun shorts(vararg values: Int): BigEndian {
            for (v in values) {
                out += (v ushr 8).toByte()
                out += v.toByte()
            }
            return this
        }

        fun raw(bytes: ByteArray) {
            for (b in bytes) out += b
        }

        fun bytes(): ByteArray = out.toByteArray()
    }
}
