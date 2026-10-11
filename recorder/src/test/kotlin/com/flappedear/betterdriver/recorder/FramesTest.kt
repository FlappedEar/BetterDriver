package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.store.Frames
import com.flappedear.betterdriver.recorder.store.Record
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class FramesTest {
    private val records = listOf(
        Record.Channel(ChannelDef(2, "gnss_lat", "deg", "gnss", 8)),
        Record.Samples(SampleSet(40_000, listOf(ChannelValue(2, 52.123456789), ChannelValue(3, -1.5)))),
        Record.Event(RecorderEvent(41_000, "video_start", "camera=phone file=a.mp4 ąę")),
        Record.Samples(SampleSet(80_000, emptyList())),
        Record.End(90_000, "user"),
    )

    private fun encodeAll(rs: List<Record>) =
        ByteArrayOutputStream().apply { rs.forEach { write(Frames.encode(it)) } }.toByteArray()

    private fun decodeAll(bytes: ByteArray): Pair<List<Record>, Frames.ScanResult> {
        val out = mutableListOf<Record>()
        val r = Frames.scan(bytes) { out += it }
        return out to r
    }

    @Test
    fun `round trips every record type`() {
        val bytes = encodeAll(records)
        val (decoded, r) = decodeAll(bytes)
        assertEquals(records, decoded)
        assertEquals(bytes.size.toLong(), r.validBytes)
        assertEquals(0, r.skippedBytes)
        assertEquals(0, r.truncatedTailBytes)
    }

    @Test
    fun `a frame cut off at any byte is dropped and everything before it is kept`() {
        val full = encodeAll(records)
        val lastLen = Frames.encode(records.last()).size
        for (cut in 1 until lastLen) {
            val (decoded, r) = decodeAll(full.copyOf(full.size - cut))
            assertEquals(records.dropLast(1), decoded, "cut $cut")
            assertEquals((lastLen - cut).toLong(), r.truncatedTailBytes + r.skippedBytes, "cut $cut")
        }
    }

    @Test
    fun `a damaged frame in the middle is skipped and later frames are kept`() {
        val frames = records.map { Frames.encode(it) }
        val bytes = encodeAll(records)
        val start = frames[0].size
        for (offset in 0 until frames[1].size) {
            val damaged = bytes.copyOf()
            damaged[start + offset] = (damaged[start + offset].toInt() xor 0x5A).toByte()
            val (decoded, _) = decodeAll(damaged)
            assertEquals(records.filterIndexed { i, _ -> i != 1 }, decoded, "offset $offset")
        }
    }

    @Test
    fun `garbage only yields nothing`() {
        val (decoded, r) = decodeAll(ByteArray(1000) { (it * 31).toByte() })
        assertEquals(emptyList(), decoded)
        assertEquals(1000L, r.skippedBytes + r.truncatedTailBytes)
    }
}
