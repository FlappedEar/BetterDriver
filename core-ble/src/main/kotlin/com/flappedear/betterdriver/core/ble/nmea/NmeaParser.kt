package com.flappedear.betterdriver.core.ble.nmea

/**
 * Longest sentence kept, from `$` to the last checksum digit. NMEA 0183 allows 82 characters with
 * CR LF, but receivers exceed it (NMEA 4.1 extra fields, long proprietary sentences), and a
 * RaceChrono `$RC3` line with all 25 fields at their widest (values down to -2000000.000) is about
 * 330, so there is room to spare. Anything longer is a run of garbage without a line end.
 */
const val MaxNmeaSentenceChars = 384

private const val Dollar = '$'.code
private const val Cr = '\r'.code
private const val Lf = '\n'.code
private const val FirstPrintable = 0x20
private const val LastPrintable = 0x7E
private const val ByteMask = 0xFF
private const val ChecksumSuffixChars = 3
private const val HexRadix = 16

/**
 * XOR of every character of [body], the part of a sentence between `$` and `*`.
 * Used by the reader to check sentences, and by tests to build them.
 */
fun nmeaChecksum(body: CharSequence): Int {
    var sum = 0
    for (c in body) sum = sum xor c.code
    return sum and ByteMask
}

/** A complete sentence with `$`, checksum and CR LF, for tests and simulators. */
fun nmeaSentence(body: String): String = "$" + body + "*%02X\r\n".format(nmeaChecksum(body))

/**
 * Cuts a Bluetooth byte stream into checked NMEA sentences. A read from the socket can end in the
 * middle of a sentence or hold several, so characters are buffered until CR or LF. A sentence
 * starts at `$`; anything before it (binary output, a half sentence from before the connection) is
 * skipped. Only sentences with a matching `*hh` checksum are passed on: Bluetooth SPP drops bytes
 * when the link is weak, and a sentence missing a few digits would put the car in a field.
 * Not thread-safe: feed it from one thread.
 */
class NmeaLineReader(private val maxChars: Int = MaxNmeaSentenceChars) {
    private val line = StringBuilder(maxChars)
    private var inSentence = false

    /** Sentences dropped because their checksum did not match. */
    var checksumErrors = 0L
        private set

    /** Sentences dropped for having no checksum, a non-printable byte, or no end. */
    var discarded = 0L
        private set

    /** Calls [onSentence] with the text between `$` and `*` of each sentence that checks out. */
    fun feed(bytes: ByteArray, onSentence: (String) -> Unit) {
        for (b in bytes) accept(b.toInt() and ByteMask, onSentence)
    }

    /** Drops a partial sentence, e.g. after the link reconnects. */
    fun reset() {
        line.setLength(0)
        inSentence = false
    }

    private fun accept(c: Int, onSentence: (String) -> Unit) {
        when {
            c == Dollar -> {
                if (inSentence) discarded++
                line.setLength(0)
                inSentence = true
            }
            c == Cr || c == Lf -> if (inSentence) {
                inSentence = false
                checked()?.let(onSentence)
            }
            !inSentence -> Unit
            c < FirstPrintable || c > LastPrintable || line.length >= maxChars -> {
                discarded++
                inSentence = false
            }
            else -> line.append(c.toChar())
        }
    }

    /** The sentence body if the line ends in a valid `*hh`, else null (and counted). */
    private fun checked(): String? {
        val star = line.length - ChecksumSuffixChars
        val expected = if (star >= 0 && line[star] == '*') line.substring(star + 1).toIntOrNull(HexRadix) else null
        return when {
            expected == null -> {
                discarded++
                null
            }
            expected != nmeaChecksum(line.subSequence(0, star)) -> {
                checksumErrors++
                null
            }
            else -> line.substring(0, star)
        }
    }
}

/**
 * Bytes in, one [NmeaEpoch] per receiver fix out: [NmeaLineReader], then [parseNmeaSentence],
 * then [NmeaEpochAssembler]. RaceChrono DIY `$RC2`/`$RC3` lines in the same stream (FBBD-115) go
 * to [parseRcLine] instead and come out one by one, outside the epochs. Pure Kotlin, so it is
 * tested on the JVM. Not thread-safe.
 */
class NmeaParser {
    private val reader = NmeaLineReader()
    private val assembler = NmeaEpochAssembler()

    /** Sentences that passed the checksum and were RMC, GGA, VTG or GSA with a usable layout. */
    var sentences = 0L
        private set

    /** `$RC2`/`$RC3` lines that passed the checksum and parsed. */
    var rcLines = 0L
        private set

    val checksumErrors: Long get() = reader.checksumErrors

    /**
     * Feeds bytes that arrived at [nowMicros] (the recorder clock). An epoch is stamped with the
     * time its first sentence arrived, because it is often only complete once the next one starts.
     * Each RaceChrono line goes to [onRcLine] as soon as it is complete.
     */
    fun feed(
        bytes: ByteArray,
        nowMicros: Long,
        onRcLine: (RcLine) -> Unit = {},
        onEpoch: (NmeaEpoch) -> Unit,
    ) {
        reader.feed(bytes) { body ->
            if (isRcLine(body)) {
                parseRcLine(body)?.let {
                    rcLines++
                    onRcLine(it)
                }
                return@feed
            }
            val sentence = parseNmeaSentence(body) ?: return@feed
            sentences++
            assembler.add(sentence, nowMicros, onEpoch)
        }
    }

    /** Forgets partial sentences and the open epoch, e.g. after the link reconnects. */
    fun reset() {
        reader.reset()
        assembler.reset()
    }
}
