package com.flappedear.betterdriver.obd

/**
 * One ECU's complete answer (FBBD-53): [bytes] from the response mode on (`41 0C 1A F8`), with any
 * ISO-TP multi-frame message put back together. [header] is the CAN id it came from (0x7E8 for the
 * engine ECU on 11-bit CAN), or null when the adapter does not show headers.
 */
class EcuMessage(val header: Int?, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is EcuMessage && other.header == header && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * (header ?: -1) + bytes.contentHashCode()

    override fun toString(): String =
        (header?.let { hex(it, if (it > Max11BitId) Id29Digits else Id11Digits) + ": " } ?: "") +
            bytes.joinToString(" ") { hex(it.toInt() and ByteMask, 2) }
}

/** What an ELM327 printed for one request, read up to its `>` prompt. */
sealed interface ElmResponse {
    /** The ECUs that answered, in the order their answers completed. */
    data class Messages(val messages: List<EcuMessage>) : ElmResponse

    /** `NO DATA`: no ECU answered, for example a PID the car does not have. */
    data object NoData : ElmResponse

    /** The adapter reported [kind]; [text] is its line as printed. */
    data class Error(val kind: ElmError, val text: String) : ElmResponse
}

/** The ELM327's error messages (ELM327 data sheet, "Messages and alerts"), plus parse failures. */
enum class ElmError {
    /** `?`: the adapter did not understand the command. */
    UNKNOWN_COMMAND,

    /** `STOPPED`: a character arrived while it was waiting, so it gave up on the request. */
    STOPPED,
    CAN_ERROR,
    BUS_ERROR,
    BUS_BUSY,
    BUFFER_FULL,
    DATA_ERROR,
    RX_ERROR,
    FB_ERROR,
    UNABLE_TO_CONNECT,
    LOW_VOLTAGE_RESET,

    /** `ERRxx` and other error lines. */
    OTHER,

    /** A line that is neither hex data nor a known message. */
    GARBLED,

    /** A multi-frame message whose frames did not all arrive, or arrived out of order. */
    INCOMPLETE,
}

/**
 * Reads ELM327 output into [ElmResponse]s (FBBD-53), for the reader in FBBD-15.
 *
 * Handles headers off (`AT H0`, data only) and on (`AT H1`, CAN id and ISO-TP PCI byte on each
 * frame), with or without spaces (`AT S0`), several ECUs answering one request, and ISO-TP
 * multi-frame answers, which mode 21/22 requests usually get:
 * - headers off: a length line (`00E`, three hex digits) and then numbered frames `0: …`, `1: …`.
 * - headers on: a first frame `7E8 10 0E …` (PCI 1, 12-bit length), then consecutive frames
 *   `7E8 21 …`, `7E8 22 …` (PCI 2, sequence number), put together per CAN id.
 * `SEARCHING...` and `BUS INIT: ...` are progress lines and are skipped; `>` is the prompt.
 *
 * Headers on is supported for CAN (11-bit ids shown as three hex digits, 29-bit as four bytes);
 * older protocols (ISO 9141, KWP) are read with headers off. Assumes `AT D0` (no DLC shown) and
 * `AT CAF1` (the default, ISO-TP formatting on).
 */
object ElmParser {
    /**
     * The response in [text], everything the adapter sent for one request. [headers] says whether
     * `AT H1` is on. [command] is the request when the adapter echoes it (`AT E1`), so that echo
     * line is dropped.
     */
    fun parse(text: String, headers: Boolean, command: String? = null): ElmResponse {
        val lines = text.replace(">", "").split('\r', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        val echo = command?.filterNot { it.isWhitespace() }?.uppercase()
        val dataLines = ArrayList<String>()
        var noData = false
        for ((i, line) in lines.withIndex()) {
            when {
                i == 0 && echo != null && line.filterNot { it.isWhitespace() } == echo -> Unit
                isProgress(line) -> Unit
                line == "NO DATA" -> noData = true
                else -> errorOf(line)?.let { return ElmResponse.Error(it, line) } ?: dataLines.add(line)
            }
        }
        return assemble(dataLines, headers, noData)
    }

    /** Lines that only say the adapter is working on it. */
    private fun isProgress(line: String): Boolean =
        line.startsWith("SEARCHING") || line == "OK" || (line.startsWith("BUS INIT") && !line.endsWith("ERROR"))

    private fun assemble(dataLines: List<String>, headers: Boolean, noData: Boolean): ElmResponse {
        val assembler = if (headers) HeadersOn() else HeadersOff()
        for (line in dataLines) {
            if (!assembler.add(line)) return ElmResponse.Error(ElmError.GARBLED, line)
        }
        val messages = assembler.finish()
        return when {
            messages.isNotEmpty() -> ElmResponse.Messages(messages)
            assembler.incomplete -> ElmResponse.Error(ElmError.INCOMPLETE, dataLines.joinToString(" | "))
            else -> if (noData || dataLines.isEmpty()) ElmResponse.NoData else ElmResponse.Messages(emptyList())
        }
    }

    /** The error on [line]; `<DATA ERROR` and `<RX ERROR` can follow data on the same line. */
    private fun errorOf(line: String): ElmError? {
        val l = line.substringAfterLast('<')
        return when {
            l == "?" -> ElmError.UNKNOWN_COMMAND
            l == "STOPPED" -> ElmError.STOPPED
            l.startsWith("UNABLE TO CONNECT") -> ElmError.UNABLE_TO_CONNECT
            l.startsWith("LV RESET") -> ElmError.LOW_VOLTAGE_RESET
            else -> ERRORS.entries.firstOrNull { l.startsWith(it.key) }?.value
                ?: ElmError.OTHER.takeIf { l.startsWith("ERR") || l.endsWith("ERROR") || l.endsWith("ALERT") }
        }
    }

    private val ERRORS = linkedMapOf(
        "CAN ERROR" to ElmError.CAN_ERROR,
        "BUS ERROR" to ElmError.BUS_ERROR,
        "BUS BUSY" to ElmError.BUS_BUSY,
        "BUFFER FULL" to ElmError.BUFFER_FULL,
        "DATA ERROR" to ElmError.DATA_ERROR,
        "RX ERROR" to ElmError.RX_ERROR,
        "FB ERROR" to ElmError.FB_ERROR,
    )
}

/** Collects data lines into messages; [add] is false for a line that is not data. */
private abstract class Assembler {
    protected val done = ArrayList<EcuMessage>()
    var incomplete = false
        protected set

    abstract fun add(line: String): Boolean

    open fun finish(): List<EcuMessage> = done
}

/**
 * `AT H0`: each line is one ECU's data, except `00E` + `0: …` `1: …` multi-frame blocks. Frame
 * numbers must run 0, 1, … F, 0, …; a repeated, missing or out-of-order frame makes the block
 * incomplete and the rest of its frames are skipped.
 */
private class HeadersOff : Assembler() {
    private var length = -1
    private var buffer: MutableList<Byte>? = null
    private var nextIndex = 0

    /** Inside a block that already went wrong: its remaining numbered frames are skipped. */
    private var discarding = false

    @Suppress("ReturnCount")
    override fun add(line: String): Boolean {
        val compact = line.filterNot { it.isWhitespace() }
        val numbered = FRAME_INDEX.matchEntire(line)
        return when {
            compact.length == LENGTH_DIGITS && compact.all { it.isHex() } -> {
                flush()
                length = compact.toInt(HexRadix)
                buffer = ArrayList()
                nextIndex = 0
                true
            }
            numbered != null -> {
                val bytes = hexBytes(numbered.groupValues[2]) ?: return false
                if (discarding) return true
                val target = buffer ?: return false
                if (numbered.groupValues[1].toInt(HexRadix) != nextIndex) {
                    buffer = null
                    discarding = true
                    incomplete = true
                    return true
                }
                nextIndex = (nextIndex + 1) % FRAME_INDICES
                bytes.forEach { target.add(it) }
                if (target.size >= length) {
                    // Complete: kept in arrival order, and any padding frames after it are skipped.
                    flush()
                    discarding = true
                }
                true
            }
            // A single-frame answer from another ECU can come between the frames of a multi-frame
            // one (FBBD-270), so it does not end the block.
            else -> hexBytes(line)?.let { done += EcuMessage(null, it) } != null
        }
    }

    override fun finish(): List<EcuMessage> {
        flush()
        return done
    }

    private fun flush() {
        discarding = false
        val b = buffer ?: return
        if (b.size >= length) done += EcuMessage(null, b.subList(0, length).toByteArray()) else incomplete = true
        buffer = null
    }

    private companion object {
        val FRAME_INDEX = Regex("""([0-9A-F]):\s*(.*)""")
        const val FRAME_INDICES = 16
        const val LENGTH_DIGITS = 3
    }
}

/** `AT H1` on CAN: every line is `<id> <PCI> <data>`, put together per id by ISO-TP frame type. */
private class HeadersOn : Assembler() {
    private class Pending(val length: Int, val bytes: MutableList<Byte>, var nextSequence: Int)

    private val pending = LinkedHashMap<Int, Pending>()

    @Suppress("ReturnCount") // Guard clauses: each is one way the line is not a frame.
    override fun add(line: String): Boolean {
        val (header, frame) = split(line) ?: return false
        if (frame.isEmpty()) return false
        val pci = frame[0].toInt() and ByteMask
        when (pci shr NIBBLE) {
            SINGLE -> {
                val length = pci and LOW_NIBBLE
                if (length == 0 || frame.size < 1 + length) return false
                done += EcuMessage(header, frame.copyOfRange(1, 1 + length))
            }
            FIRST -> {
                if (frame.size < 2) return false
                if (pending.remove(header) != null) incomplete = true
                val length = ((pci and LOW_NIBBLE) shl Byte.SIZE_BITS) or (frame[1].toInt() and ByteMask)
                pending[header] = Pending(length, frame.drop(2).toMutableList(), 1)
            }
            CONSECUTIVE -> consecutive(header, pci and LOW_NIBBLE, frame)
            else -> Unit // Flow control (3) and anything newer: not data.
        }
        return true
    }

    private fun consecutive(header: Int, sequence: Int, frame: ByteArray) {
        val p = pending[header]
        if (p == null || sequence != p.nextSequence) {
            pending.remove(header)
            incomplete = true
            return
        }
        for (i in 1 until frame.size) p.bytes.add(frame[i])
        p.nextSequence = (p.nextSequence + 1) and LOW_NIBBLE
        if (p.bytes.size >= p.length) {
            pending.remove(header)
            done += EcuMessage(header, p.bytes.subList(0, p.length).toByteArray())
        }
    }

    override fun finish(): List<EcuMessage> {
        if (pending.isNotEmpty()) incomplete = true
        return done
    }

    /** The CAN id and the frame bytes after it: 11-bit ids are three hex digits, 29-bit ids four bytes. */
    @Suppress("ReturnCount")
    private fun split(line: String): Pair<Int, ByteArray>? {
        val tokens = line.split(' ').filter { it.isNotEmpty() }
        val (idText, rest) = if (tokens.size > 1) {
            if (tokens[0].length == ID_11_DIGITS) {
                tokens[0] to tokens.drop(1).joinToString("")
            } else {
                if (tokens.size <= ID_29_BYTES) return null
                tokens.take(ID_29_BYTES).joinToString("") to tokens.drop(ID_29_BYTES).joinToString("")
            }
        } else {
            val compact = tokens.firstOrNull() ?: return null
            val idDigits = if (compact.length % 2 == 1) ID_11_DIGITS else ID_29_BYTES * 2
            if (compact.length <= idDigits) return null
            compact.take(idDigits) to compact.drop(idDigits)
        }
        if (!idText.all { it.isHex() }) return null
        val bytes = hexBytes(rest) ?: return null
        return idText.toInt(HexRadix) to bytes
    }

    private companion object {
        const val SINGLE = 0
        const val FIRST = 1
        const val CONSECUTIVE = 2
        const val NIBBLE = 4
        const val LOW_NIBBLE = 0xF
        const val ID_11_DIGITS = 3
        const val ID_29_BYTES = 4
    }
}

/** "41 0C 1A F8" or "410C1AF8" as bytes, or null when it is not whole hex bytes. */
internal fun hexBytes(text: String): ByteArray? {
    val compact = text.filterNot { it.isWhitespace() }
    if (compact.isEmpty() || compact.length % 2 != 0 || !compact.all { it.isHex() }) return null
    return ByteArray(compact.length / 2) { compact.substring(2 * it, 2 * it + 2).toInt(HexRadix).toByte() }
}

private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'A'..'F' || this in 'a'..'f'

private const val HexRadix = 16
private const val Max11BitId = 0x7FF
private const val Id11Digits = 3
private const val Id29Digits = 8
