package com.flappedear.betterdriver.obd

/**
 * The mode 01 PIDs a car supports (FBBD-53), from its answers to `01 00`, `01 20`, … `01 C0`.
 *
 * Each answer is four bytes after the echo, 32 bits for the next 32 PIDs: the most significant bit
 * of the first byte is PID base+1, the least significant bit of the last byte is PID base+0x20, which
 * also says whether the next request in the chain is worth sending (SAE J1979; see
 * https://en.wikipedia.org/wiki/OBD-II_PIDs#Service_01_PID_00). When several ECUs answer, the car
 * supports the union.
 */
object SupportedPids {
    /** The bitmask requests, in the order they are sent. */
    val bitmaskPids: List<Int> = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0, 0xC0)

    /** The PIDs set in the four [bitmask] bytes that answer `01 [base]`. */
    fun decode(base: Int, bitmask: ByteArray): Set<Int> {
        require(base in bitmaskPids) { "base $base" }
        val pids = LinkedHashSet<Int>()
        for (bit in 0 until minOf(bitmask.size, MASK_BYTES) * Byte.SIZE_BITS) {
            val byte = bitmask[bit / Byte.SIZE_BITS].toInt()
            if ((byte shr (Byte.SIZE_BITS - 1 - bit % Byte.SIZE_BITS)) and 1 == 1) pids += base + bit + 1
        }
        return pids
    }

    /**
     * The PIDs in one complete [message] (`41 00 BE 3F A8 13`), or null when it is not a bitmask
     * answer or is shorter than four data bytes.
     */
    @Suppress("ReturnCount") // Guard clauses: each is one way the message is not a bitmask.
    fun fromMessage(message: ByteArray): Set<Int>? {
        if (message.size < 2 + MASK_BYTES || message[0].toInt() and ByteMask != 0x01 + PositiveResponse) return null
        val base = message[1].toInt() and ByteMask
        if (base !in bitmaskPids) return null
        return decode(base, message.copyOfRange(2, 2 + MASK_BYTES))
    }

    /** Every PID in the bitmask answers among [messages] (all ECUs, all ranges). */
    fun fromMessages(messages: Iterable<EcuMessage>): Set<Int> =
        messages.flatMap { fromMessage(it.bytes).orEmpty() }.sorted().toSet()

    /** The next bitmask request after `01 [base]`, or null when [supported] says there is none. */
    fun nextBitmask(base: Int, supported: Set<Int>): Int? {
        val next = base + RANGE
        return next.takeIf { it in bitmaskPids && it in supported }
    }

    private const val MASK_BYTES = 4
    private const val RANGE = 0x20
}
