package com.flappedear.betterdriver.io

private const val ByteMask = 0xFF
private const val Bits8 = 8
private const val Bits16 = 16
private const val Bits24 = 24
private const val LongBytes = 8
private const val IntBytes = 4
private const val ThreeBytes = 3
private const val U16Max = 0xFFFF
private const val OneByteMax = 0x7F
private const val TwoByteMax = 0x7FF
private const val TwoByteLead = 0xC0
private const val ThreeByteLead = 0xE0
private const val ContinuationBits = 0x3F
private const val ContinuationLead = 0x80
private const val LeadMask2 = 0xE0
private const val LeadMask3 = 0xF0
private const val ContinuationMask = 0xC0
private const val Lead2Payload = 0x1F
private const val Lead3Payload = 0x0F
private const val Shift6 = 6
private const val Shift12 = 12
private const val InitialBytes = 64

/** Data that does not decode: a short read or bad modified UTF-8, as java.io's EOF and UTF errors. */
class ByteFormatException(message: String) : RuntimeException(message)

/**
 * Big-endian writer that produces the same bytes as java.io.DataOutputStream, for the session
 * format shared by Android and iOS (FBBD-70, FBBD-332).
 */
class ByteSink(initial: Int = InitialBytes) {
    private var buf = ByteArray(initial)

    var size = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
    }

    fun writeByte(v: Int) {
        ensure(1)
        buf[size++] = v.toByte()
    }

    fun writeShort(v: Int) {
        writeByte(v shr Bits8)
        writeByte(v)
    }

    fun writeInt(v: Int) {
        writeByte(v shr Bits24)
        writeByte(v shr Bits16)
        writeByte(v shr Bits8)
        writeByte(v)
    }

    fun writeLong(v: Long) {
        for (i in LongBytes - 1 downTo 0) writeByte((v shr (i * Bits8)).toInt())
    }

    /** As DataOutputStream.writeDouble: the IEEE bits with NaN made canonical. */
    fun writeDouble(v: Double) = writeLong(v.toBits())

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, size)
        size += bytes.size
    }

    /**
     * As DataOutputStream.writeUTF: a 2-byte length, then modified UTF-8 (NUL as 2 bytes, each
     * surrogate on its own in 3). Throws when that is over 65,535 bytes.
     */
    fun writeUtf(s: String) {
        var len = 0
        for (ch in s) len += utfLength(ch.code)
        if (len > U16Max) throw ByteFormatException("encoded string too long: $len bytes")
        writeShort(len)
        for (ch in s) {
            val c = ch.code
            when (utfLength(c)) {
                1 -> writeByte(c)
                2 -> {
                    writeByte(TwoByteLead or (c shr Shift6))
                    writeByte(ContinuationLead or (c and ContinuationBits))
                }
                else -> {
                    writeByte(ThreeByteLead or (c shr Shift12))
                    writeByte(ContinuationLead or ((c shr Shift6) and ContinuationBits))
                    writeByte(ContinuationLead or (c and ContinuationBits))
                }
            }
        }
    }

    private fun utfLength(c: Int) = when {
        c in 1..OneByteMax -> 1
        c <= TwoByteMax -> 2
        else -> ThreeBytes
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Big-endian reader of what [ByteSink] (or DataOutputStream) wrote, over [bytes] from [from] to [to]. */
class ByteSource(private val bytes: ByteArray, from: Int = 0, private val to: Int = bytes.size) {
    var position = from
        private set

    val remaining: Int get() = to - position

    private fun need(n: Int) {
        if (remaining < n) throw ByteFormatException("unexpected end of data")
    }

    fun readUnsignedByte(): Int {
        need(1)
        return bytes[position++].toInt() and ByteMask
    }

    fun readUnsignedShort(): Int = (readUnsignedByte() shl Bits8) or readUnsignedByte()

    fun readInt(): Int {
        need(IntBytes)
        return (readUnsignedByte() shl Bits24) or (readUnsignedByte() shl Bits16) or
            (readUnsignedByte() shl Bits8) or readUnsignedByte()
    }

    fun readLong(): Long {
        need(LongBytes)
        var v = 0L
        repeat(LongBytes) { v = (v shl Bits8) or readUnsignedByte().toLong() }
        return v
    }

    fun readDouble(): Double = Double.fromBits(readLong())

    /** As DataInputStream.readUTF, which rejects the same malformed input. */
    fun readUtf(): String {
        val len = readUnsignedShort()
        need(len)
        val end = position + len
        val out = StringBuilder(len)
        while (position < end) {
            val a = bytes[position++].toInt() and ByteMask
            when {
                a <= OneByteMax -> out.append(a.toChar())
                a and LeadMask2 == TwoByteLead -> {
                    val b = continuation(end)
                    out.append((((a and Lead2Payload) shl Shift6) or b).toChar())
                }
                a and LeadMask3 == ThreeByteLead -> {
                    val b = continuation(end)
                    val c = continuation(end)
                    out.append((((a and Lead3Payload) shl Shift12) or (b shl Shift6) or c).toChar())
                }
                else -> throw ByteFormatException("malformed input around byte ${position - 1}")
            }
        }
        return out.toString()
    }

    private fun continuation(end: Int): Int {
        if (position >= end) throw ByteFormatException("malformed input: partial character at end")
        val b = bytes[position++].toInt() and ByteMask
        if (b and ContinuationMask != ContinuationLead) {
            throw ByteFormatException("malformed input around byte ${position - 1}")
        }
        return b and ContinuationBits
    }
}
