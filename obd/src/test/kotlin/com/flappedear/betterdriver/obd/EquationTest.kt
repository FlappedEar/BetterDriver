@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import com.flappedear.betterdriver.obd.equation.Equation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-53: RaceChrono-compatible equations. Examples marked "docs" are from racechrono.com/support/equations. */
class EquationTest {
    private fun eval(equation: String, vararg raw: Int): Double? =
        assertNotNull(Equation.parse(equation), equation).evaluate(bytes(*raw))

    private fun bytes(vararg raw: Int) = ByteArray(raw.size) { raw[it].toByte() }

    private fun hexRaw(hex: String) = assertNotNull(hexBytes(hex))

    private fun evalHex(equation: String, hex: String): Double? =
        assertNotNull(Equation.parse(equation), equation).evaluate(hexRaw(hex))

    @Test
    fun `A to H are the data bytes, unsigned`() {
        assertEquals(0x1AF8 / 4.0, eval("(A*256+B)/4.0", 0x1A, 0xF8))
        assertEquals(255.0, eval("A", 0xFF))
        assertEquals(8.0, eval("H", 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(8.0, eval("R9", 1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(1.0, eval("r2", 1))
        assertEquals(eval("bytesToUint(raw, 0, 1)", 0x9C), eval("A", 0x9C))
    }

    @Test
    fun `precedence follows C and Java`() {
        assertEquals(14.0, eval("2+3*4"))
        assertEquals(20.0, eval("(2+3)*4"))
        assertEquals(1.0, eval("10-4-5"))
        assertEquals(2.0, eval("16/4/2"))
        assertEquals(0x0201.toDouble(), eval("A | B << 8", 1, 2)) // << binds tighter than |
        assertEquals(1.0, eval("1 + 2 == 3"))
        assertEquals(1.0, eval("A > 10 && B < 5", 11, 4))
        assertEquals(0.0, eval("A > 10 && B < 5", 11, 5))
        assertEquals(1.0, eval("0 || 2"))
    }

    @Test
    fun `unary minus`() {
        assertEquals(-5.0, eval("-A", 5))
        assertEquals(5.0, eval("--A", 5))
        assertEquals(-12.0, eval("-(A+1)*2", 5))
        assertEquals(-6.0, eval("2*-3"))
        assertEquals(-1.5, eval("-1.5"))
    }

    @Test
    fun `integers divide in integers, a float anywhere makes it float`() {
        assertEquals(2.0, eval("5 / 2")) // docs
        assertEquals(2.5, eval("5 / 2.0"))
        assertEquals(2.5, eval("5.0 / 2"))
        assertEquals(3.0, eval("10 % 3.5")) // docs
        assertEquals(1.0, eval("10 % 3"))
        assertEquals(-2.0, eval("-5 / 2")) // truncates toward zero, like Java
    }

    @Test
    fun `hex and float literals`() {
        assertEquals(0x30CE.toDouble(), eval("0x30CE")) // docs
        assertEquals(0x30CE.toDouble(), eval("0X30ce"))
        assertEquals(12494.0, eval("12494.0"))
        assertEquals(0.5, eval(".5"))
    }

    @Test
    fun `bit operators`() {
        assertEquals(0x0A.toDouble(), eval("A >> 4", 0xA5))
        assertEquals(0x05.toDouble(), eval("A & 0x0F", 0xA5))
        assertEquals(0x5A.toDouble(), eval("A ^ 0xFF", 0xA5))
        assertEquals(1.0, eval("(A >> 7) & 1", 0x80))
        assertEquals(0x1A00.toDouble(), eval("A << 8", 0x1A))
    }

    @Test
    fun `byte and bit functions match the docs examples`() {
        assertEquals(13124.0, evalHex("bitsToUint(raw, 24, 16)", "0011223344556677"))
        assertEquals(-19644.0, evalHex("bitsToInt(raw, 24, 16)", "001122B344556677"))
        assertEquals(47254.0, evalHex("bitsToUintLe(raw, 28, 16)", "0123456789ABCDEF"))
        assertEquals(-18282.0, evalHex("bitsToIntLe(raw, 28, 16)", "0123456789ABCDEF"))
        assertEquals(13124.0, evalHex("bytesToUint(raw, 3, 2)", "0011223344556677"))
        assertEquals(-19644.0, evalHex("bytesToInt(raw, 3, 2)", "001122B344556677"))
        assertEquals(17459.0, evalHex("bytesToUintLe(raw, 3, 2)", "0011223344556677"))
        assertEquals(-15309.0, evalHex("bytesToIntLe(raw, 3, 2)", "00112233C4556677"))
        assertEquals(2.57, evalHex("bytesToUint(raw, 0, 2) / 100.0", "0101FFFFFFFFFFFF"))
        assertEquals(89.0, assertNotNull(evalHex("bytesToUInt(raw, 0, 2) * 0.01 - 100.0", "49D4")), 1e-9)
        assertEquals(9120.0, evalHex("bitsToUInt(raw, 0, 16)", "23A0223344556677"))
        assertEquals(Math.PI.toFloat().toDouble(), evalHex("bytesToFloat(raw, 0, 4)", "40490FDB"))
        assertEquals(Math.PI.toFloat().toDouble(), evalHex("bytesToFloatLe(raw, 0, 4)", "DB0F4940"))
        assertEquals(255.0, evalHex("BYTESTOUINT(RAW, 1, 1)", "00FF")) // names are case-insensitive
    }

    @Test
    fun `other functions`() {
        assertEquals(100.0, eval("scale(50, 0, 100, 0, 200)")) // docs
        assertEquals(3.0, eval("min(A, B)", 3, 7))
        assertEquals(7.0, eval("MAX(A, B)", 3, 7))
        assertEquals(10.0, eval("abs(-10)"))
        assertEquals(8.0, eval("pow(2, 3)"))
        assertEquals(9.0, eval("pow2(A)", 3))
        assertEquals(3.0, eval("sqrt(9)"))
        assertEquals(2.0, eval("int(2.9)"))
        assertEquals(2.5, eval("float(5) / 2"))
        assertEquals(1.0, eval("if(A > 100, 1, 0)", 101))
        assertEquals(0.0, eval("if(A > 100, 1, 0)", 100))
        assertEquals(1.0, eval("isNaN(NaN)"))
        assertEquals(50.0, eval("lowPass(A, 100)", 50))
        assertNull(eval("lowPass(A, 100)", 150))
        assertNull(eval("highPass(A, 100)", 50))
    }

    @Test
    fun `bad equations do not parse`() {
        listOf(
            "", "A +", "(A", "A)", "foo(1)", "A ** 2", "AC-40", "raw", "raw + 1", "bytesToUint(A, 0, 1)",
            "bytesToUint(rawx, 0, 1)", "min(1)", "max(1, 2, 3)", "I", "R1", "1e5", "A ! B", "0x", "A 1",
            "device(1)", "99999999999999999999",
        ).forEach { assertNull(Equation.parse(it), "'$it' should not parse") }
        assertTrue(Equation.compile("A +").exceptionOrNull()?.message.orEmpty().contains("at 3"))
    }

    @Test
    fun `no value instead of a crash`() {
        assertNull(eval("B", 1)) // response too short
        assertNull(eval("bytesToUint(raw, 28, 1)", *IntArray(28)))
        assertNull(eval("bytesToUint(raw, 0, 9)", *IntArray(9))) // more than 64 bits
        assertNull(eval("bytesToFloat(raw, 0, 3)", 1, 2, 3))
        assertNull(eval("A / 0", 5))
        assertNull(eval("A % 0", 5))
        assertNull(eval("A / 0.0", 5)) // infinite
        assertNull(eval("NaN"))
        assertNull(eval("sqrt(-1)"))
        assertNull(eval("int(NaN)"))
        assertNull(eval("bytesToUint(raw, -1, 1)", 1))
    }

    @Test
    fun `one parse serves many responses`() {
        val rpm = assertNotNull(Equation.parse("(A*256+B)/4.0"))
        assertEquals(750.0, rpm.evaluate(bytes(0x0B, 0xB8)))
        assertEquals(7000.0, rpm.evaluate(bytes(0x6D, 0x60)))
        assertNull(rpm.evaluate(bytes(0x0B)))
    }
}
