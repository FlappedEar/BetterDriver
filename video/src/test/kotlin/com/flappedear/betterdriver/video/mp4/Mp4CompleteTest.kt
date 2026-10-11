package com.flappedear.betterdriver.video.mp4

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Mp4CompleteTest {
    private fun box(type: String, body: ByteArray = ByteArray(0), size: Int = 8 + body.size): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(size)
            write(type.toByteArray(Charsets.US_ASCII))
            write(body)
        }
        return out.toByteArray()
    }

    private fun file(vararg boxes: ByteArray): File =
        Files.createTempFile("clip", ".mp4").toFile().apply {
            deleteOnExit()
            writeBytes(boxes.fold(ByteArray(0)) { acc, b -> acc + b })
        }

    @Test
    fun finishedFileHasMoov() {
        assertTrue(Mp4Reader.isComplete(file(box("ftyp", ByteArray(8)), box("mdat", ByteArray(32)), box("moov"))))
    }

    @Test
    fun recordingCutOffHasNoMoov() {
        // Media data still open: its size is 0 ("to the end of the file") and no moov follows.
        assertFalse(Mp4Reader.isComplete(file(box("ftyp", ByteArray(8)), box("mdat", ByteArray(32), size = 0))))
        assertFalse(Mp4Reader.isComplete(file(box("ftyp", ByteArray(8)), box("free", ByteArray(16)))))
    }

    @Test
    fun emptyOrMissingFileIsNotComplete() {
        assertFalse(Mp4Reader.isComplete(file()))
        assertFalse(Mp4Reader.isComplete(File("does-not-exist.mp4")))
    }
}
