@file:Suppress("MagicNumber") // Byte positions of the UBX frame layout.

package com.flappedear.betterdriver.core.ble

/**
 * RaceBox packets use u-blox UBX framing (see core-ble/README.md for sources):
 * sync `B5 62`, class (u8), id (u8), payload length (u16, little-endian), payload, then the
 * two-byte Fletcher checksum CK_A, CK_B over class, id, length and payload.
 */
data class UbxFrame(val messageClass: Int, val messageId: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is UbxFrame && messageClass == other.messageClass && messageId == other.messageId &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = (messageClass * 31 + messageId) * 31 + payload.contentHashCode()
}

private const val Sync1 = 0xB5
private const val Sync2 = 0x62
private const val HeaderBytes = 6
private const val ChecksumBytes = 2
private const val ByteMask = 0xFF

/** Largest payload a RaceBox packet carries, per the RaceBox documentation as quoted by the reference client. */
const val MaxUbxPayload = 504

/** Fletcher-8 checksum over [length] bytes of [data] from [offset]. Returns CK_A in the low byte, CK_B in the next. */
fun ubxChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var a = 0
    var b = 0
    for (i in offset until offset + length) {
        a = (a + (data[i].toInt() and ByteMask)) and ByteMask
        b = (b + a) and ByteMask
    }
    return a or (b shl 8)
}

/** Builds a complete UBX packet. Used by tests and for commands sent to the device. */
fun ubxPacket(messageClass: Int, messageId: Int, payload: ByteArray): ByteArray {
    val out = ByteArray(HeaderBytes + payload.size + ChecksumBytes)
    out[0] = Sync1.toByte()
    out[1] = Sync2.toByte()
    out[2] = messageClass.toByte()
    out[3] = messageId.toByte()
    out[4] = (payload.size and ByteMask).toByte()
    out[5] = (payload.size shr 8 and ByteMask).toByte()
    payload.copyInto(out, HeaderBytes)
    val ck = ubxChecksum(out, 2, 4 + payload.size)
    out[out.size - 2] = (ck and ByteMask).toByte()
    out[out.size - 1] = (ck shr 8).toByte()
    return out
}

/**
 * Reassembles UBX frames from BLE notifications. A notification can hold part of a packet
 * (default MTU) or several packets, so bytes are buffered until a whole frame is present.
 * Bytes before a sync pattern, frames with a bad checksum and impossible lengths are skipped,
 * and reading resumes at the next sync pattern. Not thread-safe: feed it from one thread.
 */
class UbxFrameReader(private val maxPayload: Int = MaxUbxPayload) {
    private var buf = ByteArray((HeaderBytes + maxPayload + ChecksumBytes) * 2)
    private var len = 0

    /** Frames dropped because their checksum did not match. */
    var checksumErrors = 0L
        private set

    /** Bytes thrown away while looking for the next frame. */
    var skippedBytes = 0L
        private set

    fun feed(bytes: ByteArray, onFrame: (UbxFrame) -> Unit) {
        ensureRoom(bytes.size)
        bytes.copyInto(buf, len)
        len += bytes.size
        var pos = 0
        while (true) {
            val start = findSync(pos)
            skippedBytes += start - pos
            pos = start + (step(start, onFrame) ?: break)
        }
        buf.copyInto(buf, 0, pos, len)
        len -= pos
    }

    /**
     * Tries to read a frame at [pos], which starts with a sync pattern or is the end of the data.
     * Returns how many bytes to advance, or null if more data is needed.
     */
    private fun step(pos: Int, onFrame: (UbxFrame) -> Unit): Int? {
        if (len - pos < HeaderBytes) return null
        val payloadLen = u8(pos + 4) or (u8(pos + 5) shl 8)
        val total = HeaderBytes + payloadLen + ChecksumBytes
        return when {
            // Not a real header: resync from the next byte.
            payloadLen > maxPayload -> skip()
            len - pos < total -> null
            checksumOk(pos, payloadLen) -> {
                val payload = buf.copyOfRange(pos + HeaderBytes, pos + HeaderBytes + payloadLen)
                onFrame(UbxFrame(u8(pos + 2), u8(pos + 3), payload))
                total
            }
            else -> {
                checksumErrors++
                skip()
            }
        }
    }

    private fun skip(): Int {
        skippedBytes++
        return 1
    }

    private fun checksumOk(pos: Int, payloadLen: Int): Boolean {
        val ck = ubxChecksum(buf, pos + 2, 4 + payloadLen)
        val end = pos + HeaderBytes + payloadLen
        return u8(end) == ck and ByteMask && u8(end + 1) == ck shr 8
    }

    /** Drops any partial frame, e.g. after the link reconnects. */
    fun reset() {
        len = 0
    }

    /** Index of the next `B5 62` at or after [from]; a lone trailing `B5` is kept for the next feed. */
    private fun findSync(from: Int): Int {
        var i = from
        while (i < len) {
            if (u8(i) == Sync1 && (i + 1 == len || u8(i + 1) == Sync2)) return i
            i++
        }
        return len
    }

    private fun ensureRoom(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }

    private fun u8(i: Int) = buf[i].toInt() and ByteMask
}
