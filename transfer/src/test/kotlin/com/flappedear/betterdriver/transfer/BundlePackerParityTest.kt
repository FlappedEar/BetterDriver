package com.flappedear.betterdriver.transfer

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** A bundle packed in common code (the iOS share, FBBD-331) is byte for byte Android's BundleWriter's. */
class BundlePackerParityTest {
    @Test
    fun `common packer writes BundleWriter's bytes`() {
        val files = listOf(
            "bundle.properties" to "format=betterdriver-session\n".toByteArray(),
            "track.json" to "{}".toByteArray(),
            "session/chunk-00000.bin" to ByteArray(70_000) { (it % 251).toByte() },
            "session/empty.json" to ByteArray(0),
        )
        val android = ByteArrayOutputStream().also { out ->
            BundleWriter(out).apply {
                files.forEach { (path, bytes) -> file(path, bytes.size.toLong(), bytes.inputStream()) }
                finish()
            }
        }.toByteArray()
        val common = ByteArrayOutputStream().also { out ->
            BundlePacker({ b, from, to -> out.write(b, from, to - from) }).apply {
                files.forEach { (path, bytes) -> file(path, bytes) }
                finish()
            }
        }.toByteArray()
        assertContentEquals(android, common)
    }
}
