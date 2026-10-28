@file:Suppress("MagicNumber") // GPMF type sizes.

package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4FormatException
import com.flappedear.betterdriver.video.mp4.ByteReader
import com.flappedear.betterdriver.video.mp4.ascii

/** One GPMF item: FourCC key, type char, structure size, repeat count and its raw (unpadded) bytes. */
internal class Klv(val key: String, val type: Char, val structSize: Int, val repeat: Int, val bytes: ByteArray)

/**
 * GPMF KLV reading: key (4 bytes), type (1), structure size (1), repeat (2, big-endian), then
 * structure size × repeat bytes of big-endian data padded to 32 bits; type 0 is nested KLV.
 * Source: https://github.com/gopro/gpmf-parser (README).
 */
internal object Gpmf {
    /**
     * Calls [onStream] with the items of every STRM, at any depth under DEVC. GoPro nests two levels
     * (DEVC, STRM); deeper than [MaxDepth] is damage, refused before the stack runs out (audit A07).
     */
    fun forEachStream(buf: ByteReader, onStream: (List<Klv>) -> Unit) = forEachStream(buf, 0, onStream)

    private fun forEachStream(buf: ByteReader, depth: Int, onStream: (List<Klv>) -> Unit) {
        if (depth >= MaxDepth) throw Mp4FormatException("GPMF nested deeper than $MaxDepth levels")
        for (item in items(buf)) {
            when {
                item.key == "STRM" && item.type == NestedType -> onStream(items(ByteReader(item.bytes)))
                item.type == NestedType -> forEachStream(ByteReader(item.bytes), depth + 1, onStream)
            }
        }
    }

    private fun items(buf: ByteReader): List<Klv> {
        val out = mutableListOf<Klv>()
        while (buf.remaining() >= KlvHeaderBytes) {
            val key = ByteArray(4).also { buf.get(it) }.ascii()
            val type = (buf.get().toInt() and 0xFF).toChar()
            val structSize = buf.get().toInt() and 0xFF
            val repeat = buf.short.toInt() and 0xFFFF
            val length = structSize * repeat
            if (key == "\u0000\u0000\u0000\u0000") break
            if (length > buf.remaining()) throw Mp4FormatException("GPMF item $key runs past its payload")
            val bytes = ByteArray(length).also { buf.get(it) }
            val padded = (length + 3) and 3.inv()
            buf.position(minOf(buf.limit(), buf.position() + padded - length))
            out += Klv(key, type, structSize, repeat, bytes)
        }
        return out
    }

    /**
     * Every number in [item]. [types] gives one type char per field of a structure (for GPS9 it
     * comes from TYPE); otherwise the item's own type applies to every value.
     *
     * The field types are checked before any value is read: none may be missing or unknown, and a
     * structure of them must be exactly the item's structure size. So every pass reads a whole
     * structure and the loop always moves on (audit A07: an empty TYPE looped forever).
     */
    @Suppress("ThrowsCount") // One reason per way the types can be bad.
    fun numbers(item: Klv, types: String? = null): List<Double> {
        val fieldTypes = if (item.type == ComplexType) {
            types ?: throw Mp4FormatException("GPMF ${item.key} has no TYPE")
        } else {
            types?.takeIf { it.length > 1 } ?: item.type.toString()
        }
        if (fieldTypes.isEmpty()) throw Mp4FormatException("GPMF ${item.key} has an empty TYPE")
        val structBytes = fieldTypes.sumOf(::sizeOf)
        val complex = fieldTypes.length > 1
        if (complex && structBytes != item.structSize) {
            throw Mp4FormatException(
                "GPMF ${item.key} TYPE \"$fieldTypes\" is $structBytes bytes, structure ${item.structSize}",
            )
        }
        val b = ByteReader(item.bytes)
        val out = ArrayList<Double>(item.bytes.size / structBytes * fieldTypes.length)
        while (b.remaining() >= structBytes) {
            for (t in fieldTypes) out += readNumber(b, t)
        }
        return out
    }

    private fun sizeOf(t: Char): Int = when (t) {
        'b', 'B', 'c' -> 1
        's', 'S' -> 2
        'l', 'L', 'f', 'q', 'F' -> 4
        'd', 'j', 'J', 'Q' -> 8
        else -> throw Mp4FormatException("unsupported GPMF type $t")
    }

    @Suppress("CyclomaticComplexMethod")
    private fun readNumber(b: ByteReader, t: Char): Double = when (t) {
        'b' -> b.get().toDouble()
        'B', 'c' -> (b.get().toInt() and 0xFF).toDouble()
        's' -> b.short.toDouble()
        'S' -> (b.short.toInt() and 0xFFFF).toDouble()
        'l' -> b.int.toDouble()
        'L', 'F' -> (b.int.toLong() and 0xFFFFFFFFL).toDouble()
        'f' -> b.float.toDouble()
        'd' -> b.double
        'j', 'J' -> b.long.toDouble()
        'q' -> b.int / Q16
        'Q' -> b.long / Q32
        else -> throw Mp4FormatException("unsupported GPMF type $t")
    }

    private const val KlvHeaderBytes = 8
    private const val MaxDepth = 8
    private const val NestedType = '\u0000'
    private const val ComplexType = '?'
    private const val Q16 = 65536.0
    private const val Q32 = 4294967296.0
}
