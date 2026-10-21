package com.flappedear.betterdriver.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultChangesTest {
    @Test
    fun aHeldValueThatSwitchesIsNamed() {
        val changes = ResultChanges(stableSnapshots = 3)
        repeat(4) { i ->
            assertEquals(emptyList<String>(), changes.snapshot(mapOf("vendor.hdr" to "1", "exposure" to "$i")))
        }
        assertEquals(
            listOf("camera result switched: vendor.hdr 1 -> 0"),
            changes.snapshot(mapOf("vendor.hdr" to "0", "exposure" to "9")),
        )
    }

    @Test
    fun valuesThatMoveAllTheTimeAreNotNamed() {
        val changes = ResultChanges(stableSnapshots = 3)
        val lines = (0 until 20).flatMap { changes.snapshot(mapOf("exposure" to "${it % 3}")) }
        assertEquals(emptyList<String>(), lines)
    }

    @Test
    fun aKeyThatDisappearsCountsAsASwitch() {
        val changes = ResultChanges(stableSnapshots = 1)
        changes.snapshot(mapOf("vendor.mode" to "2"))
        changes.snapshot(mapOf("vendor.mode" to "2"))
        assertEquals(listOf("camera result switched: vendor.mode 2 -> absent"), changes.snapshot(emptyMap()))
    }

    @Test
    fun aValueTooLongToCompareIsNotASwitch() {
        val changes = ResultChanges(stableSnapshots = 1)
        changes.snapshot(mapOf("faces" to "[]"))
        changes.snapshot(mapOf("faces" to "[]"))
        assertEquals(emptyList<String>(), changes.snapshot(mapOf("faces" to null)))
        assertEquals(emptyList<String>(), changes.snapshot(mapOf("faces" to "[]")))
    }

    @Test
    fun linesAreCapped() {
        val changes = ResultChanges(stableSnapshots = 0, maxLines = 2)
        changes.snapshot(mapOf("a" to "1", "b" to "1", "c" to "1"))
        assertEquals(2, changes.snapshot(mapOf("a" to "2", "b" to "2", "c" to "2")).size)
        assertEquals(emptyList<String>(), changes.snapshot(mapOf("a" to "3")))
    }

    @Test
    fun snapshotsAreTenSecondsApart() {
        val changes = ResultChanges()
        assertTrue(changes.due(1_000L))
        assertFalse(changes.due(1_000L + ResultChanges.SnapshotNanos - 1))
        assertTrue(changes.due(1_000L + ResultChanges.SnapshotNanos))
    }

    @Test
    fun valuesAsText() {
        assertEquals("[1, 2]", ResultChanges.text(intArrayOf(1, 2)))
        assertEquals("[0.5]", ResultChanges.text(floatArrayOf(0.5f)))
        assertEquals("null", ResultChanges.text(null))
        assertNull(ResultChanges.text(IntArray(100)))
    }
}
