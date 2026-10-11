@file:Suppress("MagicNumber") // Field sizes and bit masks of the documented RACELOGIC Serial Stream.

package com.flappedear.betterdriver.core.ble.vbox

/**
 * The RACELOGIC Serial Stream a VBOX Sport sends over Bluetooth (FBBD-116).
 *
 * Sources:
 * - [VS] "Serial Data Format", RACELOGIC, 7/11/2014 (no revision number),
 *   https://vboxmotorsport.co.uk/downloads/VBOX-Sport_Serial_Format.pdf, linked from the VBOX Sport
 *   development portal https://vboxmotorsport.co.uk/en/vbox-sport-development-portal (10 Hz stream).
 * - [KB] "VBOX Sport Serial (RS232) Protocol", RACELOGIC Support Centre,
 *   https://en.racelogic.support/VBOX_Motorsport/Product_Info/Performance_Meters/VBOX_Sport/Knowledge_Base/VBOX_Sport_Serial_(RS232)_Protocol
 * - [V4] "VBOX 4 Serial (RS232) Protocol", RACELOGIC Support Centre, for the byte order only:
 *   https://en.racelogic.support/VBOX_Automotive/Product_Info/VBOX_Data_Loggers/VBOX_4_Product_Range/VBOX_4_Knowledge_Base/VBOX_4_Serial_(RS232)_Protocol
 * - [V2] "RS232 Data Format", RACELOGIC, 27/09/2011, for the checksum range only:
 *   https://www.racelogic.co.uk/_downloads/vbox/Application_Notes/VBOXII_rs232_Format.pdf
 *
 * A message is the ASCII header `$VBSPT$`, a 4-byte bit mask of the standard channels present, a
 * 4-byte bit mask of the extended channels present, an ASCII comma, the channels in bit order
 * (standard bits 0 to 31, then extended bits 0 to 6) with the sizes in [StandardSizes] and
 * [ExtendedSizes], then a 2-byte CRC [VS][KB]. [VS] writes the format as `$VBSPT$nnnniiii,...`
 * and [KB] as `$VBSPT$,nnnniiii,...`; [VboxSportFrameReader] accepts both and lets the CRC decide.
 *
 * Neither VBOX Sport document states the byte order. It is taken as big-endian, because the
 * VBOX 4 stream of the same family ("The $VBOX4$ and commas are in ASCII, the rest is in binary",
 * the same bit-mask layout) says "The byte ordering format is big-endian (Motorola)" [V4]. The CRC
 * range is not stated either; [V2]'s copy of the same checksum code says the string starts at `$`,
 * so it is computed from `$` up to the last data byte. Both are unverified on a real VBOX Sport: if
 * either is wrong, every message fails its CRC and nothing is recorded, rather than wrong values.
 */
object VboxSportFormat {
    const val HEADER = "\$VBSPT\$"

    /** Default channels on Bluetooth [VS][KB]: satellites to lateral acceleration. */
    const val BLUETOOTH_STANDARD = 0x0000_03FF

    /** Default extended channels on Bluetooth [VS][KB]: battery time to empty, media, HDOP. */
    const val BLUETOOTH_EXTENDED = 0x0000_0071

    const val SATELLITES = 0x0000_0001
    const val TIME = 0x0000_0002
    const val LATITUDE = 0x0000_0004
    const val LONGITUDE = 0x0000_0008
    const val SPEED = 0x0000_0010
    const val HEADING = 0x0000_0020
    const val HEIGHT = 0x0000_0040

    /** Extended channel: HDOP × 100. */
    const val EXT_HDOP = 0x0000_0040

    /**
     * Bytes of each standard channel, bit 0 first [VS][KB]: satellites, time, latitude, longitude,
     * speed, heading, height, vertical velocity, longitudinal and lateral acceleration, brake
     * distance, distance, analogue 1 to 4, GLONASS and GPS satellites, yaw 0 value / lat acc /
     * status, yaw 1 value / lat acc / status, velocity quality, temperature, buffer size, media free
     * space, event time 1 and 2, internal voltage, battery voltage.
     */
    val StandardSizes = intArrayOf(
        1, 3, 4, 4, 2, 2, 3, 2, 2, 2, 4, 4, 4, 4, 4, 4,
        1, 1, 2, 2, 2, 2, 2, 2, 4, 4, 2, 3, 4, 2, 2, 2,
    )

    /**
     * Bytes of each extended channel, bit 0 first [VS][KB]: battery time to empty, battery time to
     * full, charge when full, current charge, media capacity, media free space, HDOP. Higher bits
     * are not documented, so a message that sets one cannot be split into channels.
     */
    val ExtendedSizes = intArrayOf(2, 2, 2, 2, 4, 4, 2)

    /** Data bytes for the channels in [standard] and [extended], or null if a bit is undocumented. */
    fun dataLength(standard: Int, extended: Int): Int? {
        if (extended ushr ExtendedSizes.size != 0) return null
        return sizeOf(standard, StandardSizes) + sizeOf(extended, ExtendedSizes)
    }

    /**
     * Offset in the data of the standard channel [bit] (one of the constants above), or null if
     * the message does not carry it.
     */
    fun standardOffset(standard: Int, bit: Int): Int? =
        if (standard and bit == 0) null else sizeOf(standard and (bit - 1), StandardSizes)

    /** Offset in the data of the extended channel [bit], or null if the message does not carry it. */
    fun extendedOffset(standard: Int, extended: Int, bit: Int): Int? = if (extended and bit == 0) {
        null
    } else {
        sizeOf(standard, StandardSizes) + sizeOf(extended and (bit - 1), ExtendedSizes)
    }

    /**
     * The checksum of [VS] Note 1: CRC-16, polynomial 0x1021 (4129), initial value 0, each byte
     * XORed into the high byte, eight shifts per byte, no final XOR.
     */
    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor Polynomial else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private const val Polynomial = 0x1021

    private fun sizeOf(mask: Int, sizes: IntArray): Int {
        var total = 0
        for (bit in sizes.indices) if (mask and (1 shl bit) != 0) total += sizes[bit]
        return total
    }
}

/** One message: the channel masks and the data bytes between the comma and the CRC. */
class VboxSportMessage(val standard: Int, val extended: Int, val data: ByteArray)

private const val HeaderBytes = 7
private const val MaskBytes = 8
private const val CrcBytes = 2
private const val ByteMask = 0xFF
private const val Comma = ','.code

/** Longest possible message: every documented channel present, with the comma after the header. */
private val MaxMessageBytes = HeaderBytes + 1 + MaskBytes + 1 +
    VboxSportFormat.StandardSizes.sum() + VboxSportFormat.ExtendedSizes.sum() + CrcBytes

/**
 * Splits the byte stream of a VBOX Sport into [VboxSportMessage]s. Bytes are buffered across reads
 * until a whole message is there. Bytes before a header, messages with a bad CRC and messages with
 * undocumented channel bits are skipped, and reading resumes at the next `$VBSPT$`. Not thread-safe.
 */
class VboxSportFrameReader {
    private var buf = ByteArray(MaxMessageBytes * 4)
    private var len = 0

    /** Messages dropped because no layout matched their CRC. */
    var checksumErrors = 0L
        private set

    /** Messages dropped because they set a channel bit the format does not document. */
    var unsupportedMessages = 0L
        private set

    fun feed(bytes: ByteArray, onMessage: (VboxSportMessage) -> Unit) {
        if (len + bytes.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + bytes.size))
        bytes.copyInto(buf, len)
        len += bytes.size
        var pos = 0
        while (true) {
            pos = findHeader(pos)
            pos += step(pos, onMessage) ?: break
        }
        buf.copyInto(buf, 0, pos, len)
        len -= pos
    }

    /** Drops a partial message, e.g. after a reconnect. */
    fun reset() {
        len = 0
    }

    /** Reads the message at [pos] (a header or the end). Returns bytes to advance, or null to wait for more. */
    private fun step(pos: Int, onMessage: (VboxSportMessage) -> Unit): Int? {
        if (len - pos < HeaderBytes + 1 + MaskBytes + 1) return null
        // [KB]'s layout (comma after the header) first, then [VS]'s; the CRC tells them apart.
        val outcomes = listOf(pos + HeaderBytes + 1, pos + HeaderBytes).map { tryLayout(pos, it) }
        val found = outcomes.filterIsInstance<Outcome.Found>().firstOrNull()
        return when {
            found != null -> {
                onMessage(found.message)
                found.size
            }
            Outcome.Wait in outcomes -> null
            Outcome.Unsupported in outcomes -> {
                unsupportedMessages++
                1
            }
            else -> {
                checksumErrors++
                1
            }
        }
    }

    /** The message at [pos] read with its channel masks at [masksAt]. */
    private fun tryLayout(pos: Int, masksAt: Int): Outcome {
        val commaAt = masksAt + MaskBytes
        val headerComma = masksAt == pos + HeaderBytes || u8(pos + HeaderBytes) == Comma
        val standard = if (commaAt < len) u32(masksAt) else 0
        val extended = if (commaAt < len) u32(masksAt + 4) else 0
        val dataLen = VboxSportFormat.dataLength(standard, extended)
        val crcAt = commaAt + 1 + (dataLen ?: 0)
        return when {
            !headerComma -> Outcome.Bad
            commaAt >= len -> Outcome.Wait
            u8(commaAt) != Comma -> Outcome.Bad
            dataLen == null -> Outcome.Unsupported
            crcAt + CrcBytes > len -> Outcome.Wait
            VboxSportFormat.crc16(buf, pos, crcAt - pos) != (u8(crcAt) shl 8 or u8(crcAt + 1)) -> Outcome.Bad
            else -> Outcome.Found(
                VboxSportMessage(standard, extended, buf.copyOfRange(commaAt + 1, crcAt)),
                crcAt + CrcBytes - pos,
            )
        }
    }

    private sealed interface Outcome {
        data object Bad : Outcome
        data object Unsupported : Outcome
        data object Wait : Outcome
        class Found(val message: VboxSportMessage, val size: Int) : Outcome
    }

    /** Index of the next `$VBSPT$` at or after [from]; a partial header at the end is kept. */
    private fun findHeader(from: Int): Int {
        var i = from
        while (i < len) {
            if (headerAt(i)) return i
            i++
        }
        return len
    }

    /** Whether the bytes at [i] are the header, or the start of it with the rest still to come. */
    private fun headerAt(i: Int): Boolean =
        (0 until HeaderBytes).all { k -> i + k >= len || u8(i + k) == VboxSportFormat.HEADER[k].code }

    private fun u8(i: Int) = buf[i].toInt() and ByteMask

    private fun u32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
}
