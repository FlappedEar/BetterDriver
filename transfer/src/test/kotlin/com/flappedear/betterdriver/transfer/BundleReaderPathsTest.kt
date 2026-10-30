package com.flappedear.betterdriver.transfer

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Audit 30 Oct BD-06: the JVM reader (Android import and backup restore) refuses a bundle that holds
 * the same path twice, before the second copy is handed out, and one with too many files.
 */
class BundleReaderPathsTest {
    private fun read(bundle: ByteArray): List<String> {
        val paths = mutableListOf<String>()
        BundleReader(ByteArrayInputStream(bundle)).read { path, _, _ -> paths += path }
        return paths
    }

    @Test
    fun `a path that comes twice is refused before its second copy`() {
        val handed = mutableListOf<String>()
        val bundle = bundleOf(
            "bundle.properties" to "format=x\n".encodeToByteArray(),
            "session/meta.properties" to "a=1\n".encodeToByteArray(),
            "session/meta.properties" to "a=2\n".encodeToByteArray(),
        )
        val e = assertFailsWith<BundleFormatException> {
            BundleReader(ByteArrayInputStream(bundle)).read { path, _, _ -> handed += path }
        }
        assertEquals("session/meta.properties is in the bundle twice", e.message)
        assertEquals(listOf("bundle.properties", "session/meta.properties"), handed)
    }

    @Test
    fun `more files than the limit are refused`() {
        val many = (0..BundleFormat.MaxFiles).map { "f$it" to ByteArray(0) }
        val e = assertFailsWith<BundleFormatException> { read(bundleOf(*many.toTypedArray())) }
        assertEquals("more than ${BundleFormat.MaxFiles} files", e.message)
        assertEquals(BundleFormat.MaxFiles, read(bundleOf(*many.dropLast(1).toTypedArray())).size)
    }
}
