package com.flappedear.betterdriver.io

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A new empty folder for one test. */
expect fun newTestDir(): String

/** The shared stores' file operations behave the same on Android (JVM) and iOS (FBBD-359). */
class CoreFilesTest {
    private val dir = newTestDir()

    @AfterTest
    fun cleanUp() {
        CoreFiles.deleteRecursively(dir)
    }

    @Test
    fun writesMakeTheFoldersAndReplaceTheFileWhole() {
        val f = CoreFiles.join(CoreFiles.join(dir, "a"), "plan.json")
        CoreFiles.writeTextAtomically(f, "first Łódź")
        assertTrue(CoreFiles.isFile(f))
        assertTrue(CoreFiles.isDirectory(CoreFiles.join(dir, "a")))
        assertEquals("first Łódź", CoreFiles.readText(f))
        assertEquals("first Łódź".encodeToByteArray().size.toLong(), CoreFiles.size(f))
        CoreFiles.writeTextAtomically(f, "second")
        assertEquals("second", CoreFiles.readText(f))
        assertEquals(listOf("plan.json"), CoreFiles.list(CoreFiles.join(dir, "a")))
    }

    @Test
    fun bytesAreKeptAsTheyAreAndAFileMovesOverAnother() {
        val a = CoreFiles.join(dir, "a.properties")
        val b = CoreFiles.join(dir, "b.properties")
        val bytes = byteArrayOf(0x61, 0x3D, 0xE9.toByte(), 0x0A)
        CoreFiles.writeBytesAtomically(a, bytes)
        CoreFiles.writeTextAtomically(b, "old")
        assertTrue(bytes.contentEquals(CoreFiles.readBytes(a)))
        assertTrue(CoreFiles.move(a, b))
        assertFalse(CoreFiles.exists(a))
        assertTrue(bytes.contentEquals(CoreFiles.readBytes(b)))
        assertFalse(CoreFiles.move(a, b))
    }

    @Test
    fun listsOnlyFilesWithTheSuffix() {
        CoreFiles.writeTextAtomically(CoreFiles.join(dir, "x.json"), "{}")
        CoreFiles.writeTextAtomically(CoreFiles.join(dir, "y.txt"), "")
        CoreFiles.writeTextAtomically(CoreFiles.join(CoreFiles.join(dir, "z.json"), "inner.json"), "{}")
        assertEquals(listOf("x.json"), CoreFiles.filesEndingWith(dir, ".json"))
        assertEquals(emptyList(), CoreFiles.list(CoreFiles.join(dir, "missing")))
    }

    @Test
    fun aMissingFileIsNothingAndCannotBeRead() {
        val f = CoreFiles.join(dir, "missing.json")
        assertFalse(CoreFiles.isFile(f))
        assertFalse(CoreFiles.exists(f))
        assertEquals(0L, CoreFiles.size(f))
        assertFalse(CoreFiles.delete(f))
        assertFailsWith<IOException> { CoreFiles.readText(f) }
    }

    @Test
    fun deletesAFileOrAFolderWithEverythingInIt() {
        val f = CoreFiles.join(dir, "one.json")
        CoreFiles.writeTextAtomically(f, "1")
        assertTrue(CoreFiles.delete(f))
        assertFalse(CoreFiles.exists(f))
        val d = CoreFiles.join(dir, "track")
        CoreFiles.writeTextAtomically(CoreFiles.join(CoreFiles.join(d, "inner"), "two.json"), "2")
        assertTrue(CoreFiles.deleteRecursively(d))
        assertFalse(CoreFiles.exists(d))
        assertTrue(CoreFiles.deleteRecursively(d))
    }
}
