package com.flappedear.betterdriver.io

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The common writer, reader and CRC give exactly what java.io and java.util.zip give (FBBD-332). */
class JavaIoParityTest {
    private val random = Random(332)

    private fun randomText(): String = buildString {
        repeat(random.nextInt(0, 40)) {
            append(
                when (random.nextInt(5)) {
                    0 -> random.nextInt(0, 0x80).toChar()
                    1 -> random.nextInt(0x80, 0x800).toChar()
                    2 -> random.nextInt(0x800, 0x10000).toChar()
                    3 -> '\u0000'
                    else -> "ąęłżź🏁"[random.nextInt(7)]
                },
            )
        }
    }

    @Test
    fun `writes the same bytes as DataOutputStream`() {
        repeat(500) {
            val sink = ByteSink(1)
            val java = ByteArrayOutputStream()
            val out = DataOutputStream(java)
            val s = randomText()
            val d = when (random.nextInt(4)) {
                0 -> Double.NaN
                1 -> -0.0
                else -> random.nextDouble(-1e6, 1e6)
            }
            val l = random.nextLong()
            val i = random.nextInt()
            sink.writeUtf(s)
            out.writeUTF(s)
            sink.writeDouble(d)
            out.writeDouble(d)
            sink.writeLong(l)
            out.writeLong(l)
            sink.writeInt(i)
            out.writeInt(i)
            sink.writeShort(i)
            out.writeShort(i)
            sink.writeByte(i)
            out.writeByte(i)
            assertContentEquals(java.toByteArray(), sink.toByteArray())
            val back = ByteSource(sink.toByteArray())
            assertEquals(s, back.readUtf())
            assertEquals(d.toBits(), back.readDouble().toBits())
            assertEquals(l, back.readLong())
            assertEquals(i, back.readInt())
            assertEquals(i and 0xFFFF, back.readUnsignedShort())
            assertEquals(i and 0xFF, back.readUnsignedByte())
            assertEquals(0, back.remaining)
        }
    }

    @Test
    fun `rejects the same text as DataInputStream`() {
        repeat(5_000) {
            val len = random.nextInt(0, 6)
            val bytes = byteArrayOf(0, len.toByte()) + ByteArray(len) { random.nextInt(256).toByte() }
            val java = try {
                DataInputStream(ByteArrayInputStream(bytes)).readUTF()
            } catch (_: IOException) {
                null
            }
            val common = try {
                ByteSource(bytes).readUtf()
            } catch (_: ByteFormatException) {
                null
            }
            assertEquals(java, common, bytes.joinToString { (it.toInt() and 0xFF).toString(16) })
        }
    }

    @Test
    fun `text over 65535 encoded bytes is refused as by DataOutputStream`() {
        val s = "ą".repeat(40_000)
        assertFailsWith<IOException> { DataOutputStream(ByteArrayOutputStream()).writeUTF(s) }
        assertFailsWith<ByteFormatException> { ByteSink().writeUtf(s) }
    }

    @Test
    fun `crc matches java util zip`() {
        repeat(200) {
            val bytes = random.nextBytes(random.nextInt(0, 300))
            val b = random.nextInt()
            val java = CRC32().apply {
                update(b)
                update(bytes)
            }
            val common = Crc32().apply {
                update(b)
                update(bytes)
            }
            assertEquals(java.value.toInt(), common.value)
        }
    }
}
