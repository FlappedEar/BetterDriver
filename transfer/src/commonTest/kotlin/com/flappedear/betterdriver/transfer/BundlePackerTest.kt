package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.ByteSink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The packer writes the container BundleWriter writes, and the unpacker reads it back (FBBD-331). */
class BundlePackerTest {
    private val small = "a=b\n".encodeToByteArray()
    private val video = ByteArray(200_000) { (it * 31).toByte() }

    private fun pack(block: BundlePacker.() -> Unit): ByteArray {
        val out = ByteSink()
        BundlePacker({ b, from, to -> out.write(b.copyOfRange(from, to)) }).apply(block).finish()
        return out.toByteArray()
    }

    @Test
    fun theBytesAreTheContainersInAnyPieces() {
        val packed = pack {
            file("bundle.properties", small)
            begin("session/video/a.mp4", video.size.toLong())
            var i = 0
            while (i < video.size) {
                val n = minOf(7_777, video.size - i)
                bytes(video, i, i + n)
                i += n
            }
            end()
        }
        assertContentEquals(bundleOf("bundle.properties" to small, "session/video/a.mp4" to video), packed)
        val collect = Collect()
        BundleUnpacker(collect).apply { feed(packed) }.finish()
        assertEquals(listOf("bundle.properties", "session/video/a.mp4"), collect.files.keys.toList())
        assertContentEquals(video, collect.files.getValue("session/video/a.mp4"))
    }

    @Test
    fun aFileShorterOrLongerThanItsSizeIsRefused() {
        assertFailsWith<IllegalStateException> {
            pack {
                begin("x", 3)
                bytes(byteArrayOf(1, 2))
                end()
            }
        }
        assertFailsWith<IllegalStateException> {
            pack {
                begin("x", 1)
                bytes(byteArrayOf(1, 2))
            }
        }
        assertFailsWith<IllegalArgumentException> { pack { file("../x", small) } }
    }
}
