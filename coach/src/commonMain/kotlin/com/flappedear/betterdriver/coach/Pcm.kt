package com.flappedear.betterdriver.coach

import kotlin.math.abs
import kotlin.math.min

/**
 * Mono 16-bit audio (FBBD-48): a cue clip text-to-speech rendered, or phrases joined from clips.
 * [samples] play at [sampleRate] per second.
 */
class Pcm(val sampleRate: Int, val samples: ShortArray) {
    val durationMillis: Long get() = samples.size * MillisPerSecond / sampleRate

    /**
     * Without the near-silence text-to-speech leaves before and after a word, keeping [padMillis] on
     * each side so a word's soft start and end are not cut. All silence gives an empty clip.
     */
    fun trimmed(threshold: Int = SilenceThreshold, padMillis: Int = TrimPadMillis): Pcm {
        val first = samples.indexOfFirst { abs(it.toInt()) > threshold }
        if (first < 0) return Pcm(sampleRate, ShortArray(0))
        val last = samples.indexOfLast { abs(it.toInt()) > threshold }
        val pad = frames(padMillis)
        return Pcm(sampleRate, samples.copyOfRange((first - pad).coerceAtLeast(0), min(last + pad + 1, samples.size)))
    }

    fun frames(millis: Int): Int = (millis.toLong() * sampleRate / MillisPerSecond).toInt()

    companion object {
        /** About -38 dBFS: quieter than any spoken sound, louder than a voice engine's dither. */
        const val SilenceThreshold = 400
        const val TrimPadMillis = 15

        /**
         * [parts] played one after another, each followed by its pause in milliseconds. Null if there is
         * nothing to join or the clips differ in sample rate (they come from one voice, so they should not).
         */
        fun join(parts: List<Pair<Pcm, Int>>): Pcm? {
            val first = parts.firstOrNull()?.first?.takeIf { f -> parts.all { it.first.sampleRate == f.sampleRate } }
                ?: return null
            val rate = first.sampleRate
            val total = parts.sumOf { (pcm, pause) -> pcm.samples.size + first.frames(pause) }
            val out = ShortArray(total)
            var at = 0
            for ((pcm, pause) in parts) {
                pcm.samples.copyInto(out, at)
                at += pcm.samples.size + first.frames(pause)
            }
            return Pcm(rate, out)
        }

        private const val MillisPerSecond = 1_000L
    }
}

/**
 * Reads and writes the WAV files text-to-speech renders cue clips to (FBBD-48): RIFF, PCM, 16 bits.
 * Several channels are mixed down to mono. Anything else, or a damaged file, reads as null. Common
 * code (FBBD-48 on iOS): both apps keep their clips in the same files.
 */
@Suppress("MagicNumber", "ReturnCount") // Byte offsets of the WAV header's fields; a damaged file reads as null.
object Wav {
    fun read(bytes: ByteArray): Pcm? {
        if (bytes.size < HeaderBytes || bytes.ascii(0) != "RIFF" || bytes.ascii(8) != "WAVE") return null
        var format: Format? = null
        var at = RiffHeaderBytes
        while (at + ChunkHeaderBytes <= bytes.size) {
            val id = bytes.ascii(at)
            val size = bytes.int32(at + 4)
            val body = at + ChunkHeaderBytes
            when (id) {
                "fmt " -> format = readFormat(bytes, body, size)
                "data" -> {
                    val f = format ?: return null
                    // A file whose writer stopped early can leave the size unset: take what is there.
                    val end = if (size <= 0 || size > bytes.size - body) bytes.size else body + size
                    return samples(bytes, body, end, f)
                }
            }
            // Compared without adding, so a damaged size cannot overflow.
            if (size < 0 || size > bytes.size - body) return null
            at = body + size + (size and 1)
        }
        return null
    }

    fun write(pcm: Pcm): ByteArray {
        val dataBytes = pcm.samples.size * 2
        val b = ByteArray(HeaderBytes + dataBytes)
        "RIFF".encodeToByteArray().copyInto(b, 0)
        b.putInt32(4, HeaderBytes - ChunkHeaderBytes + dataBytes)
        "WAVE".encodeToByteArray().copyInto(b, 8)
        "fmt ".encodeToByteArray().copyInto(b, 12)
        b.putInt32(16, FmtBytes)
        b.putInt16(20, PcmFormat)
        b.putInt16(22, 1)
        b.putInt32(24, pcm.sampleRate)
        b.putInt32(28, pcm.sampleRate * 2)
        b.putInt16(32, 2)
        b.putInt16(34, Bits)
        "data".encodeToByteArray().copyInto(b, 36)
        b.putInt32(40, dataBytes)
        pcm.samples.forEachIndexed { i, s -> b.putInt16(HeaderBytes + 2 * i, s.toInt()) }
        return b
    }

    private class Format(val channels: Int, val sampleRate: Int)

    private fun readFormat(b: ByteArray, at: Int, size: Int): Format? {
        if (size < FmtBytes || at + FmtBytes > b.size) return null
        val format = b.int16(at) and 0xFFFF
        val channels = b.int16(at + 2)
        val rate = b.int32(at + 4)
        val bits = b.int16(at + 14)
        val pcm = format == PcmFormat || format == ExtensibleFormat
        return Format(channels, rate).takeIf { pcm && bits == Bits && channels >= 1 && rate > 0 }
    }

    private fun samples(b: ByteArray, from: Int, to: Int, f: Format): Pcm {
        val frameBytes = 2 * f.channels
        val n = (to - from) / frameBytes
        val out = ShortArray(n) { i ->
            val at = from + i * frameBytes
            var sum = 0
            for (c in 0 until f.channels) sum += b.int16(at + 2 * c)
            (sum / f.channels).toShort()
        }
        return Pcm(f.sampleRate, out)
    }

    private fun ByteArray.ascii(at: Int) = decodeToString(at, at + 4)

    /** Little-endian, signed. */
    private fun ByteArray.int16(at: Int): Int =
        ((this[at].toInt() and 0xFF) or (this[at + 1].toInt() shl 8)).toShort().toInt()

    private fun ByteArray.int32(at: Int): Int = (this[at].toInt() and 0xFF) or
        ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or
        (this[at + 3].toInt() shl 24)

    private fun ByteArray.putInt16(at: Int, v: Int) {
        this[at] = v.toByte()
        this[at + 1] = (v shr 8).toByte()
    }

    private fun ByteArray.putInt32(at: Int, v: Int) {
        putInt16(at, v)
        putInt16(at + 2, v shr 16)
    }

    private const val RiffHeaderBytes = 12
    private const val ChunkHeaderBytes = 8
    private const val FmtBytes = 16
    private const val HeaderBytes = RiffHeaderBytes + ChunkHeaderBytes + FmtBytes + ChunkHeaderBytes
    private const val PcmFormat = 1
    private const val ExtensibleFormat = 0xFFFE
    private const val Bits = 16
}
