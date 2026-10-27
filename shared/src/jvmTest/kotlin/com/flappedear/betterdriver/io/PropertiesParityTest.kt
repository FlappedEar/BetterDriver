package com.flappedear.betterdriver.io

import java.io.ByteArrayOutputStream
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** meta.properties written and read in common code agrees with java.util.Properties (FBBD-332). */
class PropertiesParityTest {
    private val random = Random(332)
    private val alphabet = "ab Z9 =:#!\\\t\n\r\u000Cąę€🏁.\u0000\u007F"

    private fun text() = buildString {
        repeat(random.nextInt(0, 12)) { append(alphabet[random.nextInt(alphabet.length)]) }
    }

    private fun randomMap(): Map<String, String> = (0 until random.nextInt(0, 8)).associate { text() to text() }

    private fun Properties.toMap(): Map<String, String> = stringPropertyNames().associateWith { getProperty(it) }

    @Test
    fun `java reads what common code writes`() {
        repeat(500) {
            val map = randomMap()
            val p = Properties().apply { load(writeProperties(map).inputStream()) }
            assertEquals(map, p.toMap())
        }
    }

    @Test
    fun `common code reads what java writes`() {
        repeat(500) {
            val map = randomMap()
            val bytes = ByteArrayOutputStream().also { out -> Properties().apply { putAll(map) }.store(out, "comment") }
            assertEquals(map, parseProperties(bytes.toByteArray()))
        }
    }

    @Test
    fun `hand written files read the same as java reads them`() {
        val samples = listOf(
            "a=1\nb : 2\nc 3\n  d\t=  4  \n#x=5\n!y=6\n",
            "long = one \\\n    two \\\\\nnext=\\u0105\\n\n",
            "key\\ with\\ spaces = v\r\nempty=\nonly\n",
            "a=1\\\n# not a comment\n",
            "a=\\q\\=\\:\n",
        )
        for (s in samples) {
            val java = Properties().apply { load(s.encodeToByteArray().inputStream()) }.toMap()
            assertEquals(java, parseProperties(s.encodeToByteArray()), s)
        }
    }

    @Test
    fun `a malformed unicode escape fails as in java`() {
        val bad = "a=\\u12G4\n".encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { Properties().load(bad.inputStream()) }
        assertFailsWith<IllegalArgumentException> { parseProperties(bad) }
    }
}
