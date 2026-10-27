package com.flappedear.betterdriver.obd.equation

/** Turns an equation's text into a [Node] tree: a small Pratt parser over a hand-written lexer. */
@Suppress("TooManyFunctions") // One function per grammar rule or lexer step.
internal class Parser(private val text: String) {
    private var pos = 0

    fun parseAll(): Node {
        if (text.length > Equation.MaxLength) fail("Longer than ${Equation.MaxLength} characters", 0)
        val node = expression(1)
        skipSpaces()
        if (pos < text.length) fail("Unexpected '${text[pos]}'")
        return node
    }

    private fun expression(minPrecedence: Int): Node {
        var left = unary()
        while (true) {
            val op = peekOperator()?.takeIf { it.precedence >= minPrecedence } ?: break
            pos += op.symbol.length
            left = Binary(op, left, expression(op.precedence + 1))
        }
        return left
    }

    private fun unary(): Node {
        skipSpaces()
        return when {
            accept('-') -> unary().let { if (it is IntConst || it is FloatConst) foldNegative(it) else Negate(it) }
            accept('+') -> unary()
            else -> primary()
        }
    }

    private fun foldNegative(constant: Node): Node =
        if (constant.isInt) IntConst(-constant.long(EMPTY)) else FloatConst(-constant.double(EMPTY))

    private fun primary(): Node {
        skipSpaces()
        val c = text.getOrNull(pos) ?: fail("Missing value")
        return when {
            c == '(' -> {
                pos++
                expression(1).also { expect(')') }
            }
            c.isDigit() || c == '.' -> number()
            c.isLetter() || c == '_' -> identifier()
            else -> fail("Unexpected '$c'")
        }
    }

    private fun number(): Node {
        val start = pos
        if (text.startsWith("0x", pos, ignoreCase = true)) return hexNumber(start)
        while (pos < text.length && text[pos].isDigit()) pos++
        val isFloat = pos < text.length && text[pos] == '.'
        if (isFloat) {
            pos++
            while (pos < text.length && text[pos].isDigit()) pos++
        }
        val literal = text.substring(start, pos)
        return if (isFloat) {
            FloatConst(literal.toDoubleOrNull() ?: fail("Bad number", start))
        } else {
            IntConst(literal.toLongOrNull() ?: fail("Number out of range", start))
        }
    }

    /** `0x30CE`: up to 64 bits, read as unsigned. */
    private fun hexNumber(start: Int): Node {
        pos += 2
        while (pos < text.length && text[pos].isHexDigit()) pos++
        val digits = text.substring(start + 2, pos)
        if (digits.isEmpty() || digits.length > HEX_DIGITS) fail("Bad hexadecimal number", start)
        return IntConst(digits.toULong(HEX).toLong())
    }

    private fun identifier(): Node {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) pos++
        val name = text.substring(start, pos)
        skipSpaces()
        if (accept('(')) return call(name, start)
        return variable(name.lowercase()) ?: fail("Unknown variable '$name'", start)
    }

    /** `A`..`H` and `R2`..`R9` are the data bytes 0..7; `NaN` is the hidden value. */
    private fun variable(name: String): Node? = when {
        name.length == 1 && name[0] in 'a'..'h' -> ByteVar(name[0] - 'a')
        name.length == 2 && name[0] == 'r' && name[1] in '2'..'9' -> ByteVar(name[1] - '2')
        name == "nan" -> FloatConst(Double.NaN)
        else -> null
    }

    private fun call(name: String, start: Int): Node {
        val fn = Fn.named(name) ?: fail("Unknown function '$name'", start)
        if (fn.rawSource) {
            skipSpaces()
            val end = pos + RAW.length
            val isRaw = text.startsWith(RAW, pos, ignoreCase = true) &&
                text.getOrNull(end)?.let { it.isLetterOrDigit() || it == '_' } != true
            if (!isRaw) fail("$name needs raw as its source")
            pos = end
            expect(',')
        }
        val args = ArrayList<Node>()
        skipSpaces()
        if (!accept(')')) {
            do {
                args += expression(1)
                skipSpaces()
            } while (accept(','))
            expect(')')
        }
        if (args.size != fn.arity) fail("$name takes ${fn.arity + if (fn.rawSource) 1 else 0} arguments", start)
        return when (fn) {
            Fn.BYTESTOFLOAT, Fn.BYTESTOFLOATLE -> RawFloat(fn == Fn.BYTESTOFLOATLE, args[0], args[1])
            Fn.BYTESTOUINT, Fn.BYTESTOINT, Fn.BYTESTOUINTLE, Fn.BYTESTOINTLE,
            Fn.BITSTOUINT, Fn.BITSTOINT, Fn.BITSTOUINTLE, Fn.BITSTOINTLE,
            -> rawInt(fn, args)
            else -> Call(fn, args)
        }
    }

    private fun rawInt(fn: Fn, args: List<Node>): Node {
        val n = fn.name
        return RawInt(
            bits = n.startsWith("BITS"),
            signed = !n.contains("UINT"),
            littleEndian = n.endsWith("LE"),
            offset = args[0],
            length = args[1],
        )
    }

    private fun peekOperator(): BinaryOp? {
        skipSpaces()
        if (pos >= text.length) return null
        val two = if (pos + 1 < text.length) BinaryOp.bySymbol[text.substring(pos, pos + 2)] else null
        return two ?: BinaryOp.bySymbol[text[pos].toString()]
    }

    private fun accept(c: Char): Boolean {
        skipSpaces()
        if (pos < text.length && text[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun expect(c: Char) {
        if (!accept(c)) fail("Expected '$c'")
    }

    private fun skipSpaces() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun fail(message: String, at: Int = pos): Nothing = throw EquationSyntaxException(message, at)

    private fun Char.isHexDigit(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'

    private companion object {
        const val HEX = 16
        const val HEX_DIGITS = 16
        const val RAW = "raw"
        val EMPTY = ByteArray(0)
    }
}
