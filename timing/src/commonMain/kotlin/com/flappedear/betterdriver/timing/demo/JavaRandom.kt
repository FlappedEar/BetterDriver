package com.flappedear.betterdriver.timing.demo

/**
 * java.util.Random's generator in common Kotlin (FBBD-373), so the demo circuit drives the same laps on
 * Android and iOS as it always has on Android: the same 48-bit linear congruential sequence, and the
 * same nextDouble and nextBoolean built from it.
 */
internal class JavaRandom(seed: Long) {
    private var seed = (seed xor Multiplier) and Mask

    private fun next(bits: Int): Int {
        seed = (seed * Multiplier + Addend) and Mask
        return (seed ushr (SeedBits - bits)).toInt()
    }

    fun nextDouble(): Double = ((next(HighBits).toLong() shl LowBits) + next(LowBits)) * DoubleUnit

    fun nextBoolean(): Boolean = next(1) != 0

    private companion object {
        const val Multiplier = 0x5DEECE66DL
        const val Addend = 0xBL
        const val SeedBits = 48
        const val Mask = (1L shl SeedBits) - 1
        const val HighBits = 26
        const val LowBits = 27
        val DoubleUnit = 1.0 / (1L shl 53).toDouble()
    }
}

/** [count] draws from [seed], every third a nextBoolean as 1.0 or 0.0: for the JVM test against java.util.Random. */
internal fun javaRandomDoubles(seed: Long, count: Int): List<Double> {
    val r = JavaRandom(seed)
    return List(count) {
        if (it % BooleanEvery == BooleanEvery - 1) (if (r.nextBoolean()) 1.0 else 0.0) else r.nextDouble()
    }
}

private const val BooleanEvery = 3
