@file:Suppress("MagicNumber") // GPMF type sizes.

package com.flappedear.betterdriver.video.gpmf

import com.flappedear.betterdriver.video.mp4.Mp4FormatException
import java.nio.ByteBuffer

/** One GPMF item: FourCC key, type char, structure size, repeat count and its raw (unpadded) bytes. */
internal class Klv(val key: String, val type: Char, val structSize: Int, val repeat: Int, val bytes: ByteArray)

/**
 * GPMF KLV reading: key (4 bytes), type (1), structure size (1), repeat (2, big-endian), then
 * structure size × repeat bytes of big-endian data padded to 32 bits; type 0 is nested KLV.
 * Source: https://github.com/gopro/gpmf-parser (README).
 */
internal object Gpmf {
    /** Calls [onStream] with the items of every STRM, at any depth under DEVC. */
    fun forEachStream(buf: ByteBuffer, onStream: (List<Klv>) -> Unit) {
        for (item in items(buf)) {
            when {
                item.key == "STRM" && item.type == NestedType -> onStream(items(ByteBuffer.wrap(item.bytes)))
                item.type == NestedType -> forEachStream(ByteBuffer.wrap(item.bytes), onStream)
            }
        }
    }

    private fun items(buf: ByteBuffer): List<Klv> {
        val out = mutableListOf<Klv>()
        while (buf.remaining() >= KlvHeaderBytes) {
            val key = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
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
     */
    fun numbers(item: Klv, types: String? = null): List<Double> {
        val fieldTypes = types?.takeIf { item.type == ComplexType || it.length > 1 } ?: item.type.toString()
        val b = ByteBuffer.wrap(item.bytes)
        val out = mutableListOf<Double>()
        while (b.hasRemaining()) {
            for (t in fieldTypes) {
                if (b.remaining() < sizeOf(t)) return out
                out += readNumber(b, t)
            }
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
    private fun readNumber(b: ByteBuffer, t: Char): Double = when (t) {
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
    private const val NestedType = '\u0000'
    private const val ComplexType = '?'
    private const val Q16 = 65536.0
    private const val Q32 = 4294967296.0
}
