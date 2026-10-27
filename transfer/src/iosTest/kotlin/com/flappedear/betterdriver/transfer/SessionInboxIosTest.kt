@file:OptIn(ExperimentalForeignApi::class)

package com.flappedear.betterdriver.transfer

import com.flappedear.betterdriver.io.writeProperties
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.store.SessionFileNames
import com.flappedear.betterdriver.recorder.store.metaEntries
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackJson
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.flock
import platform.posix.open
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionInboxIosTest {
    private val fm = NSFileManager.defaultManager
    private val root = NSTemporaryDirectory() + "inbox-" + NSUUID().UUIDString
    private val inbox = SessionInbox("$root/sessions", "$root/staging", "$root/tracks")
    private val id = "20250829-160659-abcd1234"
    private val track = Track(id = "jastrzab", name = "Jastrząb", center = GeoPoint(51.0, 19.0))

    @AfterTest
    fun cleanUp() {
        fm.removeItemAtPath(root, null)
    }

    private fun write(bytes: ByteArray): String {
        fm.createDirectoryAtPath(root, true, null, null)
        val path = "$root/${NSUUID().UUIDString}.bdsession"
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        assertTrue(fm.createFileAtPath(path, data, null))
        return path
    }

    private fun bundle(car: String = "FR-S", video: Int = 150_000, track: Track = this.track) = bundleOf(
        "bundle.properties" to writeProperties(
            mapOf("format" to SessionBundleReceiver.Format, "version" to "1", "session" to id, "track" to track.id),
        ),
        "track.json" to TrackJson.encode(track).encodeToByteArray(),
        "session/" + SessionFileNames.META to
            writeProperties(metaEntries(id, SessionInfo(1_756_476_419_000L, car = car))),
        "session/video/phone_0001.mp4" to ByteArray(video) { it.toByte() },
    )

    @Test
    fun importsASessionWithItsVideoAndTrack() {
        var last = 0L
        val received = inbox.receive(write(bundle())) { read, total ->
            assertTrue(read >= last)
            last = read
            assertTrue(read <= total)
        }
        assertEquals(id, received.session.sessionId)
        assertFalse(inbox.exists(received))
        assertTrue(inbox.accept(received, replace = false))
        assertTrue(fm.fileExistsAtPath("$root/sessions/$id/${SessionFileNames.META}"))
        assertTrue(fm.fileExistsAtPath("$root/sessions/$id/video/phone_0001.mp4"))
        assertTrue(fm.fileExistsAtPath("$root/tracks/${track.id}.json"))
        assertEquals(emptyList(), fm.contentsOfDirectoryAtPath("$root/staging", null).orEmpty())
    }

    @Test
    fun keepsOrReplacesASessionAlreadyHere() {
        inbox.accept(inbox.receive(write(bundle(car = "first"))), replace = false)
        val second = inbox.receive(write(bundle(car = "second")))
        assertTrue(inbox.exists(second))
        assertFalse(inbox.accept(second, replace = false))
        val third = inbox.receive(write(bundle(car = "third")))
        assertTrue(inbox.accept(third, replace = true))
        assertTrue(fm.fileExistsAtPath("$root/sessions/$id/${SessionFileNames.META}"))
        assertEquals(emptyList(), fm.contentsOfDirectoryAtPath("$root/staging", null).orEmpty())
    }

    @Test
    fun aDamagedBundleLeavesNothingBehind() {
        val good = bundle()
        assertFailsWith<BundleDamagedException> { inbox.receive(write(good.copyOf(good.size - 10))) }
        assertEquals(emptyList(), fm.contentsOfDirectoryAtPath("$root/staging", null).orEmpty())
        assertFalse(fm.fileExistsAtPath("$root/sessions/$id"))
    }

    @Test
    fun neverReplacesASessionStillRecording() {
        inbox.accept(inbox.receive(write(bundle(car = "first"))), replace = false)
        val lock = open("$root/sessions/$id/${SessionFileNames.LOCK}", O_WRONLY or O_CREAT, 420u)
        assertTrue(lock >= 0 && flock(lock, LOCK_EX or LOCK_NB) == 0)
        try {
            val second = inbox.receive(write(bundle(car = "second")))
            assertFailsWith<ImportFileException> { inbox.accept(second, replace = true) }
            assertTrue(fm.fileExistsAtPath("$root/sessions/$id/${SessionFileNames.META}"))
            assertEquals(emptyList(), fm.contentsOfDirectoryAtPath("$root/staging", null).orEmpty())
        } finally {
            flock(lock, LOCK_UN)
            close(lock)
        }
    }

    @Test
    fun aChangedTrackIsKeptOrReplacedOnRequest() {
        inbox.accept(inbox.receive(write(bundle())), replace = false)
        val moved = track.copy(name = "Jastrząb new lines")
        val second = inbox.receive(write(bundle(track = moved)))
        assertTrue(inbox.trackDiffers(second))
        inbox.accept(second, replace = true, replaceTrack = false)
        val third = inbox.receive(write(bundle(track = moved)))
        assertTrue(inbox.trackDiffers(third))
        inbox.accept(third, replace = true, replaceTrack = true)
        assertFalse(inbox.trackDiffers(inbox.receive(write(bundle(track = moved)))))
    }
}
