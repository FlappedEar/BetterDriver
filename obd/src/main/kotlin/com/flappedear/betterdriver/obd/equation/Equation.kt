package com.flappedear.betterdriver.obd.equation

/**
 * A RaceChrono-compatible channel equation (FBBD-53), parsed once into a tree and evaluated per
 * response. Profiles written for RaceChrono can be copied into BetterDriver unchanged.
 *
 * The syntax follows RaceChrono's "Equations reference" (https://racechrono.com/support/equations):
 * - `raw` is the PID's response data, without the mode and PID echo (for `41 0C 1A F8`, raw is
 *   `1A F8`). `A` to `H` (also `R2` to `R9`) are its first eight bytes: `A` is
 *   `bytesToUint(raw, 0, 1)`. That page defines `A` that way, the J1979 formulas RaceChrono uses for
 *   mode 01 count `A` from the first byte after the echo, and community profiles carry Torque's
 *   byte letters over 1:1 (Torque's `AC` becomes `bytesToUint(raw, 28, 1)`, see the 86/BRZ thread at
 *   https://racechrono.com/forum/discussion/comment/10650), so the echo is not part of `raw`.
 * - Integer constants (`12`), hexadecimal integers (`0x30CE`) and floating-point constants with a
 *   dot (`1.1`). Values are 64-bit integers or doubles; an operation on two integers stays an
 *   integer, so `5 / 2` is 2 and `(A*256+B)*7/1800` drops the fraction just as RaceChrono does.
 *   Write `100.0` to divide in floating point. Integer division truncates like Java (the docs say
 *   "rounds down", which is the same for the non-negative values in their examples).
 * - Operators with C/Java precedence: `* / %`, `+ -`, `<< >>`, `< > <= >=`, `== !=`, `&`, `^`, `|`,
 *   `&&`, `||`, unary minus, and brackets. Comparisons and logic give 1 or 0; zero is false.
 * - Functions (names are case-insensitive): `bytesToUint`, `bytesToInt`, `bytesToUintLe`,
 *   `bytesToIntLe`, `bytesToFloat`, `bytesToFloatLe` (source, byteOffset, byteLength); `bitsToUint`,
 *   `bitsToInt`, `bitsToUintLe`, `bitsToIntLe` (source, bitOffset, bitLength; big-endian offset 0 is
 *   the most significant bit of the first byte, little-endian offset 0 the least significant bit
 *   of the first byte); `min`, `max`, `abs`, `pow`, `pow2`, `sqrt`, `float`, `int`, `if`,
 *   `scale`, `lowPass`, `highPass`, `isNaN`; and the constant `NaN`. The source of the byte and
 *   bit functions must be `raw`. `device()` and `channel()` (DIY API channels) are not supported.
 *
 * Equations are at most [MaxLength] characters, which keeps the parser's and the evaluator's
 * recursion shallow (RaceChrono equations are a line or two).
 *
 * Nothing here throws on bad input: [parse] returns null for an equation that does not parse, and
 * [evaluate] returns null when the response is too short, a division by zero happens, or the result
 * is NaN or infinite. The caller hides the channel then.
 */
class Equation private constructor(
    /** The equation as written. */
    val source: String,
    private val root: Node,
) {
    /** The value for one response's [raw] data (after the echo), or null when there is none. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun evaluate(raw: ByteArray): Double? = try {
        root.double(raw).takeIf { it.isFinite() }
    } catch (_: EvalFailure) {
        null
    } catch (_: RuntimeException) {
        // Every expected failure is an EvalFailure; this is the backstop that keeps a channel's
        // equation from ever taking the reader down.
        null
    }

    override fun toString(): String = source

    companion object {
        /** The longest equation accepted. */
        const val MaxLength = 1000

        /** [source] parsed, or null when it is not a valid equation. */
        fun parse(source: String): Equation? = compile(source).getOrNull()

        /** [source] parsed, or an [EquationSyntaxException] saying what is wrong and where. */
        fun compile(source: String): Result<Equation> = try {
            Result.success(Equation(source, Parser(source).parseAll()))
        } catch (e: EquationSyntaxException) {
            Result.failure(e)
        }
    }
}

/** Why an equation does not parse; [position] is the character index in the equation. */
class EquationSyntaxException(message: String, val position: Int) : Exception("$message at $position")
