package com.flappedear.betterdriver.obd.equation

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The functions of RaceChrono's equations reference (https://racechrono.com/support/equations),
 * by lower-case name. [rawSource] functions take `raw` as their first argument; [arity] counts the
 * remaining ones for those and all of them for the others.
 */
@Suppress("MagicNumber")
internal enum class Fn(val arity: Int, val rawSource: Boolean = false) {
    BYTESTOUINT(2, true), BYTESTOINT(2, true), BYTESTOUINTLE(2, true), BYTESTOINTLE(2, true),
    BYTESTOFLOAT(2, true), BYTESTOFLOATLE(2, true),
    BITSTOUINT(2, true), BITSTOINT(2, true), BITSTOUINTLE(2, true), BITSTOINTLE(2, true),
    MIN(2), MAX(2), ABS(1), POW(2), POW2(1), SQRT(1), FLOAT(1), INT(1),
    IF(3), SCALE(5), LOWPASS(2), HIGHPASS(2), ISNAN(1),
    ;

    companion object {
        fun named(name: String): Fn? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** `bytesTo…(raw, offset, length)` and `bitsTo…(raw, offset, length)` as integers. */
internal class RawInt(
    private val bits: Boolean,
    private val signed: Boolean,
    private val littleEndian: Boolean,
    private val offset: Node,
    private val length: Node,
) : IntNode() {
    override fun long(raw: ByteArray): Long {
        val off = offset.long(raw)
        val len = length.long(raw)
        val unitBits = if (bits) 1 else Byte.SIZE_BITS
        val width = len * unitBits
        val fits = off >= 0 && len >= 1 && width <= Long.SIZE_BITS &&
            (off + len) * unitBits <= raw.size.toLong() * Byte.SIZE_BITS
        if (!fits) throw EvalFailure
        val firstBit = (off * unitBits).toInt()
        val value = if (littleEndian) bitsLe(raw, firstBit, width.toInt()) else bitsBe(raw, firstBit, width.toInt())
        return if (signed) signExtend(value, width.toInt()) else value
    }
}

/** `bytesToFloat(raw, offset, 4 or 8)`, IEEE 754, big- or little-endian. */
internal class RawFloat(
    private val littleEndian: Boolean,
    private val offset: Node,
    private val length: Node,
) : FloatNode() {
    override fun double(raw: ByteArray): Double {
        val off = offset.long(raw)
        val len = length.long(raw)
        val validLength = len == FloatBytes || len == DoubleBytes
        if (!validLength || off < 0 || off + len > raw.size) throw EvalFailure
        val firstBit = (off * Byte.SIZE_BITS).toInt()
        val width = (len * Byte.SIZE_BITS).toInt()
        val bits = if (littleEndian) bitsLe(raw, firstBit, width) else bitsBe(raw, firstBit, width)
        return if (len == FloatBytes) Float.fromBits(bits.toInt()).toDouble() else Double.fromBits(bits)
    }
}

/** [width] bits from bit [first], msb-0: bit 0 is the most significant bit of the first byte. */
private fun bitsBe(raw: ByteArray, first: Int, width: Int): Long {
    var v = 0L
    for (i in first until first + width) {
        val bit = (raw[i / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - i % Byte.SIZE_BITS)) and 1
        v = (v shl 1) or bit.toLong()
    }
    return v
}

/** [width] bits from bit [first], lsb-0 little-endian: bit 0 is the lowest bit of the first byte. */
private fun bitsLe(raw: ByteArray, first: Int, width: Int): Long {
    var v = 0L
    for (j in 0 until width) {
        val i = first + j
        val bit = (raw[i / Byte.SIZE_BITS].toInt() shr (i % Byte.SIZE_BITS)) and 1
        v = v or (bit.toLong() shl j)
    }
    return v
}

private fun signExtend(v: Long, width: Int): Long =
    if (width >= Long.SIZE_BITS) v else (v shl (Long.SIZE_BITS - width)) shr (Long.SIZE_BITS - width)

/**
 * The other functions. Integer-valued when every input is: `min`, `max`, `abs`, `pow2` and `if` of
 * integers; `int` and `isNaN` always.
 */
internal class Call(private val fn: Fn, private val args: List<Node>) : Node() {
    override val isInt: Boolean = when (fn) {
        Fn.MIN, Fn.MAX, Fn.ABS, Fn.POW2 -> args.all { it.isInt }
        Fn.IF -> args[1].isInt && args[2].isInt
        Fn.INT, Fn.ISNAN -> true
        else -> false
    }

    override fun long(raw: ByteArray): Long = if (!isInt) {
        truncate(double(raw))
    } else {
        when (fn) {
            Fn.MIN -> min(args[0].long(raw), args[1].long(raw))
            Fn.MAX -> max(args[0].long(raw), args[1].long(raw))
            Fn.ABS -> abs(args[0].long(raw))
            Fn.POW2 -> args[0].long(raw).let { it * it }
            Fn.IF -> if (truthy(args[0], raw)) args[1].long(raw) else args[2].long(raw)
            Fn.INT -> args[0].long(raw)
            else -> args[0].double(raw).isNaN().toLong()
        }
    }

    override fun double(raw: ByteArray): Double = if (isInt) long(raw).toDouble() else floatValue(raw)

    @Suppress("CyclomaticComplexMethod") // One branch per function.
    private fun floatValue(raw: ByteArray): Double {
        val a = args[0].double(raw)
        return when (fn) {
            Fn.MIN -> min(a, args[1].double(raw))
            Fn.MAX -> max(a, args[1].double(raw))
            Fn.ABS -> abs(a)
            Fn.POW -> a.pow(args[1].double(raw))
            Fn.POW2 -> a * a
            Fn.SQRT -> sqrt(a)
            Fn.FLOAT -> a
            Fn.IF -> if (a != 0.0) args[1].double(raw) else args[2].double(raw)
            Fn.LOWPASS -> if (a > args[1].double(raw)) Double.NaN else a
            Fn.HIGHPASS -> if (a < args[1].double(raw)) Double.NaN else a
            else -> scale(a, raw)
        }
    }

    /** `scale(source, oldA, oldB, newA, newB)`: oldA..oldB mapped linearly onto newA..newB. */
    @Suppress("MagicNumber") // Argument positions.
    private fun scale(source: Double, raw: ByteArray): Double {
        val oldA = args[1].double(raw)
        val oldB = args[2].double(raw)
        val newA = args[3].double(raw)
        val newB = args[4].double(raw)
        return newA + (source - oldA) * (newB - newA) / (oldB - oldA)
    }
}

private const val FloatBytes = 4L
private const val DoubleBytes = 8L
