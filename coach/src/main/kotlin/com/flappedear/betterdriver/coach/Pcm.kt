package com.flappedear.betterdriver.coach

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * Mono 16-bit audio (FBBD-48, FBBD-49): a cue clip text-to-speech rendered, phrases joined from clips,
 * and the delay test's click. [samples] play at [sampleRate] per second.
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

        /**
         * A short, sharp tone for the delay test (FBBD-49): [millis] of [hz] with a quick fade in and out
         * so it does not crackle. Easy to hear over road noise and starts at once, unlike a word.
         */
        fun click(sampleRate: Int, millis: Int = ClickMillis, hz: Double = ClickHz): Pcm {
            val n = (millis.toLong() * sampleRate / MillisPerSecond).toInt()
            val fade = (n / ClickFadeParts).coerceAtLeast(1)
            val samples = ShortArray(n) { i ->
                val envelope = min(1.0, min(i, n - 1 - i).toDouble() / fade)
                (sin(2 * PI * hz * i / sampleRate) * envelope * ClickAmplitude).toInt().toShort()
            }
            return Pcm(sampleRate, samples)
        }

        private const val MillisPerSecond = 1_000L
        private const val ClickMillis = 40
        private const val ClickHz = 1_500.0
        private const val ClickFadeParts = 8
        private const val ClickAmplitude = 0.6 * Short.MAX_VALUE
    }
}

/**
 * Reads and writes the WAV files text-to-speech renders cue clips to (FBBD-48): RIFF, PCM, 16 bits.
 * Several channels are mixed down to mono. Anything else, or a damaged file, reads as null.
 */
@Suppress("MagicNumber", "ReturnCount") // Byte offsets of the WAV header's fields; a damaged file reads as null.
object Wav {
    fun read(bytes: ByteArray): Pcm? {
        if (bytes.size < HeaderBytes || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            return null
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var format: Format? = null
        var at = RiffHeaderBytes
        while (at + ChunkHeaderBytes <= bytes.size) {
            val id = String(bytes, at, 4, Charsets.US_ASCII)
            val size = b.getInt(at + 4)
            val body = at + ChunkHeaderBytes
            when (id) {
                "fmt " -> format = readFormat(b, body, size)
                "data" -> {
                    val f = format ?: return null
                    // A file whose writer stopped early can leave the size unset: take what is there.
                    val end = if (size <= 0 || size > bytes.size - body) bytes.size else body + size
                    return samples(b, body, end, f)
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
        val b = ByteBuffer.allocate(HeaderBytes + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(HeaderBytes - ChunkHeaderBytes + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII)).putInt(FmtBytes)
        b.putShort(PcmFormat.toShort()).putShort(1).putInt(pcm.sampleRate).putInt(pcm.sampleRate * 2)
        b.putShort(2).putShort(Bits.toShort())
        b.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataBytes)
        pcm.samples.forEach { b.putShort(it) }
        return b.array()
    }

    private class Format(val channels: Int, val sampleRate: Int)

    private fun readFormat(b: ByteBuffer, at: Int, size: Int): Format? {
        if (size < FmtBytes || at + FmtBytes > b.limit()) return null
        val format = b.getShort(at).toInt() and 0xFFFF
        val channels = b.getShort(at + 2).toInt()
        val rate = b.getInt(at + 4)
        val bits = b.getShort(at + 14).toInt()
        val pcm = format == PcmFormat || format == ExtensibleFormat
        return Format(channels, rate).takeIf { pcm && bits == Bits && channels >= 1 && rate > 0 }
    }

    private fun samples(b: ByteBuffer, from: Int, to: Int, f: Format): Pcm {
        val frameBytes = 2 * f.channels
        val n = (to - from) / frameBytes
        val out = ShortArray(n) { i ->
            val at = from + i * frameBytes
            var sum = 0
            for (c in 0 until f.channels) sum += b.getShort(at + 2 * c)
            (sum / f.channels).toShort()
        }
        return Pcm(f.sampleRate, out)
    }

    private const val RiffHeaderBytes = 12
    private const val ChunkHeaderBytes = 8
    private const val FmtBytes = 16
    private const val HeaderBytes = RiffHeaderBytes + ChunkHeaderBytes + FmtBytes + ChunkHeaderBytes
    private const val PcmFormat = 1
    private const val ExtensibleFormat = 0xFFFE
    private const val Bits = 16
}
