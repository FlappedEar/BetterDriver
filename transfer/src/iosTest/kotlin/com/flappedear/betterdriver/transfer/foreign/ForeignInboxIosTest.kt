@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.flappedear.betterdriver.transfer.foreign

import com.flappedear.betterdriver.recorder.store.SessionState
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.timing.live.LapKind
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.TrackDetection
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The iOS import (FBBD-331) end to end: a file on disk becomes a session that SessionStore.read reads. */
class ForeignInboxIosTest {
    private val fm = NSFileManager.defaultManager
    private val root = NSTemporaryDirectory() + "foreign-" + NSUUID().UUIDString
    private val inbox = ForeignInbox("$root/sessions", "$root/staging")

    @AfterTest
    fun cleanUp() {
        fm.removeItemAtPath(root, null)
    }

    private fun write(bytes: ByteArray, name: String): String {
        fm.createDirectoryAtPath(root, true, null, null)
        val path = "$root/$name"
        val data = if (bytes.isEmpty()) {
            NSData()
        } else {
            bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        }
        assertTrue(fm.createFileAtPath(path, data, null))
        return path
    }

    private fun stagingEmpty() = fm.contentsOfDirectoryAtPath("$root/staging", null).orEmpty().isEmpty()

    @Test
    fun `a VBO file becomes a finished session in the list`() {
        val path = write(SyntheticCircle.vbo().encodeToByteArray(), "circle.vbo")
        var read = 0L
        var total = 0L
        val made = inbox.importFile(path, "circle.vbo", listOf(SyntheticCircle.track), CircuitList.Empty) { r, t ->
            read = r
            total = t
        }
        assertEquals(600, made.points)
        assertEquals(total, read)
        assertTrue(made.detection is TrackDetection.Saved)
        val store = SessionStore("$root/sessions")
        val listed = store.list().single()
        assertEquals(made.id, listed.id)
        assertEquals(SessionState.FINISHED, listed.state)
        assertEquals(59_900_000L, listed.durationMicros)
        val data = store.read(made.id)
        assertEquals(600, data.sampleSets.size)
        assertEquals("circle.vbo", data.info.imported?.fileName)
        val laps = replayLaps(made.id, data.sampleSets, SyntheticCircle.track)
        assertEquals(2, laps.laps.count { it.lap.kind == LapKind.Timed })
        assertTrue(stagingEmpty())
    }

    @Test
    fun `files that cannot be imported leave nothing behind`() {
        val cases = listOf(
            "empty" to ByteArray(0),
            "not a session" to "hello".encodeToByteArray(),
            "gzip" to byteArrayOf(0x1f, 0x8b.toByte(), 8, 0),
            "gpx" to "<?xml version=\"1.0\"?><gpx version=\"1.1\"></gpx>".encodeToByteArray(),
            "one row" to SyntheticCircle.vbo(rows = 1).encodeToByteArray(),
        )
        for ((what, bytes) in cases) {
            val path = write(bytes, "$what.file")
            assertFailsWith<ImportFormatException>(what) {
                inbox.importFile(path, what, listOf(SyntheticCircle.track), CircuitList.Empty)
            }
            assertTrue(SessionStore("$root/sessions").list().isEmpty(), what)
            assertTrue(fm.fileExistsAtPath("$root/staging").not() || stagingEmpty(), what)
        }
    }
}
