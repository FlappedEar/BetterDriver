package com.flappedear.betterdriver.canreader

/** Diagnostic trouble codes in a mode 03, 07 or 0A answer (SAE J1979), for the log only (FBBD-319). */
object Dtc {
    /**
     * The codes in one ECU's answer (`43 02 01 33 00 00`): two bytes a code, the first two bits the
     * system (P, C, B, U). On CAN the byte after the mode is the number of codes, which an odd
     * count of bytes gives away. `00 00` pairs are padding. Only a positive answer (43, 47, 4A)
     * has codes.
     */
    fun codes(message: ByteArray): List<String> {
        // A refusal (7F 07 11) or any other answer has no codes.
        if (message.firstOrNull()?.toInt() !in POSITIVE) return emptyList()
        val data = message.drop(1)
        val pairs = if (data.size % 2 == 1) data.drop(1) else data
        return pairs.chunked(2).filter { it.size == 2 && (it[0].toInt() != 0 || it[1].toInt() != 0) }.map { (a, b) ->
            val hi = a.toInt() and BYTE_MASK
            val system = "PCBU"[hi shr SYSTEM_SHIFT]
            val first = (hi shr DIGIT_SHIFT) and DIGIT_MASK
            "%c%d%X%02X".format(system, first, hi and NIBBLE_MASK, b.toInt() and BYTE_MASK)
        }
    }

    private val POSITIVE = setOf(0x43, 0x47, 0x4A)
    private const val BYTE_MASK = 0xFF
    private const val SYSTEM_SHIFT = 6
    private const val DIGIT_SHIFT = 4
    private const val DIGIT_MASK = 0x3
    private const val NIBBLE_MASK = 0xF
}
