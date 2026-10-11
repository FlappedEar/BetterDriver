package com.flappedear.betterdriver.recorder.store

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.RecorderEvent
import com.flappedear.betterdriver.recorder.SampleSet
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

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
 * All integers are big-endian.
 */
sealed interface Record {
    data class Channel(val def: ChannelDef) : Record
    data class Samples(val set: SampleSet) : Record
    data class Event(val event: RecorderEvent) : Record
    /** Written on a clean stop. A session without it was interrupted. */
    data class End(val tMicros: Long, val reason: String) : Record
}

object Frames {
    const val MAGIC = 0x42445231
    private const val MAGIC_BYTES = 4
    private const val INT_BYTES = 4
    private const val BYTE_MASK = 0xFF
    private const val U16_MAX = 0xFFFF
    const val HEADER_BYTES = MAGIC_BYTES + 1 + INT_BYTES
    const val TRAILER_BYTES = INT_BYTES
    const val MAX_PAYLOAD = 1 shl 20

    private const val T_CHANNEL: Int = 1
    private const val T_SAMPLES: Int = 2
    private const val T_EVENT: Int = 3
    private const val T_END: Int = 4

    fun encode(record: Record): ByteArray {
        val payload = ByteArrayOutputStream()
        val out = DataOutputStream(payload)
        val type = when (record) {
            is Record.Channel -> {
                out.writeShort(record.def.id)
                out.writeUTF(record.def.name)
                out.writeUTF(record.def.unit)
                out.writeUTF(record.def.source)
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
                out.writeUTF(record.event.kind)
                out.writeUTF(record.event.text)
                T_EVENT
            }
            is Record.End -> {
                out.writeLong(record.tMicros)
                out.writeUTF(record.reason)
                T_END
            }
        }
        out.flush()
        val body = payload.toByteArray()
        val frame = ByteBuffer.allocate(HEADER_BYTES + body.size + TRAILER_BYTES)
        frame.putInt(MAGIC)
        frame.put(type.toByte())
        frame.putInt(body.size)
        frame.put(body)
        frame.putInt(crc(type, body))
        return frame.array()
    }

    private fun crc(type: Int, body: ByteArray): Int {
        val c = CRC32()
        c.update(type)
        c.update(ByteBuffer.allocate(INT_BYTES).putInt(body.size).array())
        c.update(body)
        return c.value.toInt()
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
        val buf = ByteBuffer.wrap(data)
        var pos = 0
        var records = 0
        var valid = 0L
        var skipped = 0L
        var truncated = 0L
        while (pos < data.size) {
            when (val f = frameAt(buf, pos)) {
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

    private fun frameAt(buf: ByteBuffer, pos: Int): FrameAt {
        val remaining = buf.limit() - pos
        val len = if (remaining >= HEADER_BYTES) buf.getInt(pos + MAGIC_BYTES + 1) else -1
        val frameLen = HEADER_BYTES + len + TRAILER_BYTES
        return when {
            remaining < HEADER_BYTES + TRAILER_BYTES -> FrameAt.Tail
            buf.getInt(pos) != MAGIC || len < 0 || len > MAX_PAYLOAD -> FrameAt.Invalid(1)
            // Either the tail written during a crash, or a damaged length field: look for a later frame.
            frameLen > remaining -> findMagic(buf, pos + 1, buf.limit()).let { next ->
                if (next < 0) FrameAt.Tail else FrameAt.Invalid(next - pos)
            }
            else -> {
                val type = buf.get(pos + MAGIC_BYTES).toInt() and BYTE_MASK
                val body = buf.array().copyOfRange(pos + HEADER_BYTES, pos + HEADER_BYTES + len)
                val record = if (buf.getInt(pos + HEADER_BYTES + len) == crc(type, body)) decode(type, body) else null
                if (record == null) FrameAt.Invalid(1) else FrameAt.Valid(record, frameLen)
            }
        }
    }

    private fun findMagic(buf: ByteBuffer, from: Int, end: Int): Int {
        var i = from
        while (i + MAGIC_BYTES <= end) {
            if (buf.getInt(i) == MAGIC) return i
            i++
        }
        return -1
    }

    private fun decode(type: Int, body: ByteArray): Record? = try {
        val inp = DataInputStream(body.inputStream())
        val r = when (type) {
            T_CHANNEL -> Record.Channel(
                ChannelDef(
                    id = inp.readUnsignedShort(),
                    name = inp.readUTF(),
                    unit = inp.readUTF(),
                    source = inp.readUTF(),
                    decimals = inp.readUnsignedByte(),
                ),
            )
            T_SAMPLES -> {
                val t = inp.readLong()
                val n = inp.readUnsignedShort()
                Record.Samples(SampleSet(t, List(n) { ChannelValue(inp.readUnsignedShort(), inp.readDouble()) }))
            }
            T_EVENT -> Record.Event(RecorderEvent(inp.readLong(), inp.readUTF(), inp.readUTF()))
            T_END -> Record.End(inp.readLong(), inp.readUTF())
            else -> null
        }
        if (inp.available() != 0) null else r
    } catch (_: java.io.IOException) {
        null // truncated or malformed payload
    } catch (_: IllegalArgumentException) {
        null // e.g. a channel definition with out-of-range values
    }
}
