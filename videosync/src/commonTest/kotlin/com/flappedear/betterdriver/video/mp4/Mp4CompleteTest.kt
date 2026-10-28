package com.flappedear.betterdriver.video.mp4

import com.flappedear.betterdriver.video.IoProblem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether an MP4 is finished, the same on Android and iOS (audit BD-07): a moov box counts only when
 * the whole box is in the file.
 */
class Mp4CompleteTest {
    private class Bytes(private val bytes: ByteArray) : Mp4Source {
        override val length: Long = bytes.size.toLong()

        override fun read(offset: Long, size: Int): ByteArray {
            if (offset < 0 || offset + size > length) throw IoProblem("past the end", null)
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + size)
        }

        override fun close() = Unit
    }

    private fun box(type: String, body: Int, size: Int = 8 + body) =
        byteArrayOf((size shr 24).toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte()) +
            type.encodeToByteArray() + ByteArray(body)

    private fun complete(bytes: ByteArray) = Mp4Parser.isComplete(Bytes(bytes))

    @Test
    fun aWholeFileIsComplete() {
        assertTrue(complete(box("ftyp", 12) + box("mdat", 100) + box("moov", 20)))
    }

    @Test
    fun aMoovHeaderWithoutItsBodyIsNot() {
        // 00 00 10 00 'moov': 4096 bytes declared, 8 present.
        assertFalse(complete(box("moov", 0, size = 4096)))
        assertFalse(complete(box("ftyp", 12) + box("moov", 20, size = 4096)))
    }

    @Test
    fun aCutExtendedHeaderIsNot() {
        // Size 1: a 64-bit size follows, of which only 4 bytes made it.
        assertFalse(complete(box("ftyp", 12) + byteArrayOf(0, 0, 0, 1) + "moov".encodeToByteArray() + ByteArray(4)))
    }

    @Test
    fun aBoxSmallerThanItsHeaderIsNot() {
        assertFalse(complete(box("free", 0, size = 4) + box("moov", 20)))
    }

    @Test
    fun aFileWithoutMoovIsNot() {
        assertFalse(complete(box("ftyp", 12) + box("mdat", 100)))
        assertFalse(complete(ByteArray(0)))
    }
}
