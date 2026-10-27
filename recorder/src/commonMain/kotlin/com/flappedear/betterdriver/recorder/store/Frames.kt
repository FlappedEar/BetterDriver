package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.io.ByteFormatException
import com.flappedear.betterdriver.io.ByteSink
import com.flappedear.betterdriver.io.ByteSource
import com.flappedear.betterdriver.io.Crc32
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.PackedValues
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet

/**
 * On-disk record framing for chunk files.
 *
 * Every frame is self-checking, so a chunk cut off by a crash or power loss is read up to its
 * last complete frame, and a damaged frame in the middle is skipped:
 *
 * ```
 * magic   u32  0x42445231 ("BDR1")
 * type    u8
 * length  u32  payload bytes
 * payload
 * crc32   u32  over type, length and payload
 * ```
 * All integers are big-endian. Text is java.io's modified UTF-8, so Android and iOS write and
 * read the same bytes (FBBD-332).
 */

object Frames {
    const val MAGIC = 0x42445231
    private const val MAGIC_BYTES = 4
    private const val INT_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val U16_MAX = 0xFFFF
    const val HEADER_BYTES = MAGIC_BYTES + 1 + INT_BYTES
    const val TRAILER_BYTES = INT_BYTES
    const val MAX_PAYLOAD = 1 shl 20
    const val MAX_TEXT_CHARS = 8_192

    private const val T_CHANNEL: Int = 1
    private const val T_SAMPLES: Int = 2
    private const val T_EVENT: Int = 3
    private const val T_END: Int = 4

    fun encode(record: Record): ByteArray {
        val out = ByteSink()
        val type = when (record) {
            is Record.Channel -> {
                out.writeShort(record.def.id)
                out.writeUtf(record.def.name)
                out.writeUtf(record.def.unit)
                out.writeUtf(record.def.source)
                out.writeByte(record.def.decimals)
                T_CHANNEL
            }
            is Record.Samples -> {
                require(record.set.values.size <= U16_MAX) { "too many values in one set" }
                out.writeLong(record.set.tMicros)
                out.writeShort(record.set.values.size)
                for (v in record.set.values) {
                    out.writeShort(v.channelId)
                    out.writeDouble(v.value)
                }
                T_SAMPLES
            }
            is Record.Event -> {
                out.writeLong(record.event.tMicros)
                out.writeUtf(bounded(record.event.kind))
                out.writeUtf(bounded(record.event.text))
                T_EVENT
            }
            is Record.End -> {
                out.writeLong(record.tMicros)
                out.writeUtf(bounded(record.reason))
                T_END
            }
        }
        val body = out.toByteArray()
        val frame = ByteSink(HEADER_BYTES + body.size + TRAILER_BYTES)
        frame.writeInt(MAGIC)
        frame.writeByte(type)
        frame.writeInt(body.size)
        frame.write(body)
        frame.writeInt(crc(type, body, 0, body.size))
        return frame.toByteArray()
    }

    /**
     * Free text (events, stop reasons) can come from anywhere; cut it so writeUTF can never throw
     * on the writer thread. [MAX_TEXT_CHARS] chars encode to at most 3 bytes each, under 65,535.
     */
    private fun bounded(s: String): String {
        if (s.length <= MAX_TEXT_CHARS) return s
        val end = if (s[MAX_TEXT_CHARS - 1].isHighSurrogate()) MAX_TEXT_CHARS - 1 else MAX_TEXT_CHARS
        return s.substring(0, end)
    }

    private fun crc(type: Int, data: ByteArray, from: Int, to: Int): Int {
        val c = Crc32()
        c.update(type)
        val len = ByteSink(INT_BYTES).apply { writeInt(to - from) }.toByteArray()
        c.update(len)
        c.update(data, from, to)
        return c.value
    }

    /** Result of scanning one chunk. [validBytes] counts bytes in frames that passed their checksum. */
    data class ScanResult(
        val records: Int,
        val validBytes: Long,
        val skippedBytes: Long,
        val truncatedTailBytes: Long,
    )

    /**
     * Decodes every valid frame in [data] in order. A damaged frame is skipped by searching for the
     * next magic number; an incomplete frame at the end (a crash mid-write) is dropped.
     */
    fun scan(data: ByteArray, onRecord: (Record) -> Unit): ScanResult {
        var pos = 0
        var records = 0
        var valid = 0L
        var skipped = 0L
        var truncated = 0L
        while (pos < data.size) {
            when (val f = frameAt(data, pos)) {
                is FrameAt.Valid -> {
                    onRecord(f.record)
                    records++
                    valid += f.length
                    pos += f.length
                }
                is FrameAt.Invalid -> {
                    skipped += f.skip
                    pos += f.skip
                }
                FrameAt.Tail -> {
                    truncated = (data.size - pos).toLong()
                    pos = data.size
                }
            }
        }
        return ScanResult(records, valid, skipped, truncated)
    }

    private sealed interface FrameAt {
        data class Valid(val record: Record, val length: Int) : FrameAt
        /** Not a frame here: skip [skip] bytes and look again. */
        data class Invalid(val skip: Int) : FrameAt
        /** The rest of the data is an incomplete frame. */
        data object Tail : FrameAt
    }

    private fun frameAt(data: ByteArray, pos: Int): FrameAt {
        val remaining = data.size - pos
        val len = if (remaining >= HEADER_BYTES) intAt(data, pos + MAGIC_BYTES + 1) else -1
        val frameLen = HEADER_BYTES + len + TRAILER_BYTES
        return when {
            remaining < HEADER_BYTES + TRAILER_BYTES -> FrameAt.Tail
            intAt(data, pos) != MAGIC || len < 0 || len > MAX_PAYLOAD -> FrameAt.Invalid(1)
            // Either the tail written during a crash, or a damaged length field: look for a later frame.
            frameLen > remaining -> findMagic(data, pos + 1).let { next ->
                if (next < 0) FrameAt.Tail else FrameAt.Invalid(next - pos)
            }
            else -> {
                val type = data[pos + MAGIC_BYTES].toInt() and BYTE_MASK
                val from = pos + HEADER_BYTES
                val ok = intAt(data, from + len) == crc(type, data, from, from + len)
                val record = if (ok) decode(type, data, from, from + len) else null
                if (record == null) FrameAt.Invalid(1) else FrameAt.Valid(record, frameLen)
            }
        }
    }

    private fun intAt(data: ByteArray, pos: Int): Int = ByteSource(data, pos).readInt()

    private fun findMagic(data: ByteArray, from: Int): Int {
        var i = from
        while (i + MAGIC_BYTES <= data.size) {
            if (intAt(data, i) == MAGIC) return i
            i++
        }
        return -1
    }

    private fun decode(type: Int, data: ByteArray, from: Int, to: Int): Record? = try {
        val inp = ByteSource(data, from, to)
        val r = when (type) {
            T_CHANNEL -> Record.Channel(
                ChannelDef(
                    id = inp.readUnsignedShort(),
                    name = inp.readUtf(),
                    unit = inp.readUtf(),
                    source = inp.readUtf(),
                    decimals = inp.readUnsignedByte(),
                ),
            )
            T_SAMPLES -> {
                val t = inp.readLong()
                val n = inp.readUnsignedShort()
                val ids = CharArray(n)
                val values = DoubleArray(n)
                for (i in 0 until n) {
                    ids[i] = inp.readUnsignedShort().toChar()
                    values[i] = inp.readDouble()
                }
                // Packed: a long session read back is millions of values (FBBD-232).
                Record.Samples(SampleSet(t, PackedValues(ids, values)))
            }
            T_EVENT -> Record.Event(RecorderEvent(inp.readLong(), inp.readUtf(), inp.readUtf()))
            T_END -> Record.End(inp.readLong(), inp.readUtf())
            else -> null
        }
        if (inp.remaining != 0) null else r
    } catch (_: ByteFormatException) {
        null // truncated or malformed payload
    } catch (_: IllegalArgumentException) {
        null // e.g. a channel definition with out-of-range values
    }
}
