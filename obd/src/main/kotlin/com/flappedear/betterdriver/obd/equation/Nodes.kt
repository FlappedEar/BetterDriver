package com.flappedear.betterdriver.obd.equation

/**
 * Evaluation stops here: the response is too short, an integer division by zero, NaN where an
 * integer is needed. Thrown without a stack trace, since it is an expected outcome per response.
 */
internal object EvalFailure : RuntimeException(null, null, false, false)

/**
 * One node of a parsed equation. Its type is fixed when it is parsed: [isInt] nodes compute in
 * 64-bit integers ([long]), the others in doubles ([double]). Evaluation allocates nothing.
 */
internal sealed class Node {
    abstract val isInt: Boolean

    abstract fun long(raw: ByteArray): Long

    abstract fun double(raw: ByteArray): Double
}

/** An integer-valued node; its double is its integer. */
internal abstract class IntNode : Node() {
    override val isInt: Boolean get() = true

    override fun double(raw: ByteArray): Double = long(raw).toDouble()
}

/** A double-valued node; as an integer it is truncated toward zero, and NaN has none. */
internal abstract class FloatNode : Node() {
    override val isInt: Boolean get() = false

    override fun long(raw: ByteArray): Long = truncate(double(raw))
}

internal class IntConst(private val value: Long) : IntNode() {
    override fun long(raw: ByteArray): Long = value
}

internal class FloatConst(private val value: Double) : FloatNode() {
    override fun double(raw: ByteArray): Double = value
}

/** `A` to `H`: one unsigned byte of the data. */
internal class ByteVar(private val index: Int) : IntNode() {
    override fun long(raw: ByteArray): Long {
        if (index >= raw.size) throw EvalFailure
        return (raw[index].toInt() and ByteMask).toLong()
    }
}

internal class Negate(private val operand: Node) : Node() {
    override val isInt: Boolean get() = operand.isInt

    override fun long(raw: ByteArray): Long = if (isInt) -operand.long(raw) else truncate(double(raw))

    override fun double(raw: ByteArray): Double = if (isInt) long(raw).toDouble() else -operand.double(raw)
}

/** A double as an integer, truncated toward zero like Java's cast; NaN has none. */
internal fun truncate(d: Double): Long {
    if (d.isNaN()) throw EvalFailure
    return d.toLong()
}

/** C/Java binary operators; a higher [precedence] binds tighter. */
@Suppress("MagicNumber")
internal enum class BinaryOp(val symbol: String, val precedence: Int) {
    OR("||", 1), AND("&&", 2),
    BIT_OR("|", 3), BIT_XOR("^", 4), BIT_AND("&", 5),
    EQ("==", 6), NE("!=", 6),
    LT("<", 7), GT(">", 7), LE("<=", 7), GE(">=", 7),
    SHL("<<", 8), SHR(">>", 8),
    ADD("+", 9), SUB("-", 9),
    MUL("*", 10), DIV("/", 10), REM("%", 10),
    ;

    /** Arithmetic keeps the operands' type; comparisons, logic and bit operations give integers. */
    val arithmetic: Boolean get() = this == ADD || this == SUB || this == MUL || this == DIV || this == REM

    companion object {
        val bySymbol: Map<String, BinaryOp> = entries.associateBy { it.symbol }
    }
}

internal class Binary(private val op: BinaryOp, private val left: Node, private val right: Node) : Node() {
    override val isInt: Boolean = !op.arithmetic || (left.isInt && right.isInt)

    /** Integer arithmetic only when both sides are integers; comparisons in doubles otherwise. */
    private val intOperands = left.isInt && right.isInt

    override fun long(raw: ByteArray): Long = when {
        !isInt -> truncate(double(raw))
        op.arithmetic -> intArithmetic(left.long(raw), right.long(raw))
        else -> intResult(raw)
    }

    override fun double(raw: ByteArray): Double = if (isInt) {
        long(raw).toDouble()
    } else {
        floatArithmetic(left.double(raw), right.double(raw))
    }

    private fun intArithmetic(a: Long, b: Long): Long = when (op) {
        BinaryOp.ADD -> a + b
        BinaryOp.SUB -> a - b
        BinaryOp.MUL -> a * b
        BinaryOp.DIV -> if (b == 0L) throw EvalFailure else a / b
        else -> if (b == 0L) throw EvalFailure else a % b
    }

    private fun floatArithmetic(a: Double, b: Double): Double = when (op) {
        BinaryOp.ADD -> a + b
        BinaryOp.SUB -> a - b
        BinaryOp.MUL -> a * b
        BinaryOp.DIV -> a / b
        else -> a % b
    }

    private fun intResult(raw: ByteArray): Long = when (op) {
        BinaryOp.OR -> (truthy(left, raw) || truthy(right, raw)).toLong()
        BinaryOp.AND -> (truthy(left, raw) && truthy(right, raw)).toLong()
        BinaryOp.BIT_OR -> left.long(raw) or right.long(raw)
        BinaryOp.BIT_XOR -> left.long(raw) xor right.long(raw)
        BinaryOp.BIT_AND -> left.long(raw) and right.long(raw)
        BinaryOp.SHL -> left.long(raw) shl right.long(raw).toInt()
        BinaryOp.SHR -> left.long(raw) shr right.long(raw).toInt()
        else -> compare(raw)
    }

    private fun compare(raw: ByteArray): Long {
        val c = if (intOperands) {
            left.long(raw).compareTo(right.long(raw))
        } else {
            val a = left.double(raw)
            val b = right.double(raw)
            // Any comparison with NaN is false, except !=.
            if (a.isNaN() || b.isNaN()) return (op == BinaryOp.NE).toLong()
            a.compareTo(b)
        }
        return when (op) {
            BinaryOp.EQ -> c == 0
            BinaryOp.NE -> c != 0
            BinaryOp.LT -> c < 0
            BinaryOp.GT -> c > 0
            BinaryOp.LE -> c <= 0
            else -> c >= 0
        }.toLong()
    }
}

/** Zero is false, anything else (NaN included) is true. */
internal fun truthy(node: Node, raw: ByteArray): Boolean =
    if (node.isInt) node.long(raw) != 0L else node.double(raw) != 0.0

internal fun Boolean.toLong(): Long = if (this) 1L else 0L

internal const val ByteMask = 0xFF
