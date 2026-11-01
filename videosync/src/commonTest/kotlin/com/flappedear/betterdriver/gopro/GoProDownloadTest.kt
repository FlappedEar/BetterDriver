package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.Alignment
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.IoProblem
import com.flappedear.betterdriver.video.VideoClip
import com.flappedear.betterdriver.video.VideoSourceKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The download after the drive, shared by Android and the iPhone (FBBD-22, FBBD-343), on a phone made of maps. */
class GoProDownloadTest {
    private val start = 1_760_180_000_000L

    /** A camera's media and a session folder in memory; [unreadable] files come whole but are no MP4. */
    private class FakeHost(var media: List<MediaFile>) : GoProDownloadHost {
        val folder = mutableMapOf<String, Long>()
        val recordings = mutableListOf<GoProRecording>()
        val unreadable = mutableSetOf<String>()
        var joined: Pair<String, String>? = null
        var left = false
        var failJoin = false
        val fetched = mutableListOf<String>()

        override suspend fun join(ssid: String, password: String) {
            if (failJoin) throw IoProblem("could not join $ssid", null)
            joined = ssid to password
        }

        override suspend fun leave() {
            left = true
        }

        override suspend fun mediaList() = media

        override fun size(localName: String) = folder[localName]

        override suspend fun download(file: MediaFile, localName: String, onProgress: (Long, Long) -> Unit) {
            onProgress(file.size / 2, file.size)
            onProgress(file.size, file.size)
            folder[localName] = file.size
            fetched += localName
        }

        override suspend fun import(recording: GoProRecording, localNames: List<String>): Placement {
            val good = localNames.filter { it !in unreadable }
            val byGps = Alignment(AlignmentMethod.GPS_MATCH, 50_000)
            val clips = good.map { VideoClip(it, VideoSourceKind.GOPRO, recording.sourceId, 0, byGps) }
            return Placement(clips, (localNames - good.toSet()).map { "$it: not an MP4" })
        }

        override suspend fun readable(localName: String) = localName !in unreadable

        override fun delete(localName: String) {
            folder.remove(localName)
        }

        override fun recordings() = recordings.toList()

        override fun putRecording(recording: GoProRecording) {
            recordings.removeAll {
                it.sourceId == recording.sourceId && it.startAckUtcMillis == recording.startAckUtcMillis
            }
            recordings += recording
        }
    }

    private fun recording(sourceId: String = "gopro-x") = GoProRecording(
        sourceId, startAckSessionUs = 4_000_000, startAckUtcMillis = start + 4_000,
        stopAckSessionUs = 200_000_000, stopAckUtcMillis = start + 200_000, cameraUtcOffsetMinutes = 0,
    )

    private val created = (start + 5_000) / 1000
    private val twoChapters = listOf(
        MediaFile("100GOPRO", "GX010041.MP4", created - 3600, 10),
        MediaFile("100GOPRO", "GX010009.MP4", created, 4_000),
        MediaFile("100GOPRO", "GX020009.MP4", created, 3_000),
    )

    @Test
    fun downloadsTheSessionsChaptersLinesThemUpAndTurnsTheAccessPointOff() = runTest {
        val camera = FakeGoPro()
        val host = FakeHost(twoChapters).apply { recordings += recording() }
        val progress = mutableListOf<GoProDownload.Progress>()
        val result = GoProDownload(camera, backgroundScope, host).run("gopro-x") { progress += it }

        assertEquals(emptyList(), result.problems)
        assertEquals(listOf("GX010009.MP4", "GX020009.MP4"), result.downloaded)
        assertEquals("GP12345678" to "secret-pass", host.joined)
        assertTrue(host.left)
        // AP on, then AP off at the end.
        assertEquals(listOf(0x17, 0x17), camera.ids)
        assertEquals(0, camera.requests.last().second[2].toInt())
        assertEquals(1, camera.disconnects)
        // Saved under the camera's id, so two cameras' GX010009.MP4 never meet.
        assertEquals(setOf("gopro-x-GX010009.MP4", "gopro-x-GX020009.MP4"), host.folder.keys)
        assertEquals(listOf(1, 1, 2, 2), progress.map { it.index })
        assertEquals(3_000, progress.last().bytes)
        assertTrue(host.recordings.single().downloaded)

        // A second run has nothing left to do and does not even connect.
        val again = GoProDownload(camera, backgroundScope, host).run("gopro-x")
        assertEquals(emptyList(), again.downloaded)
        assertEquals(1, camera.connects)
    }

    @Test
    fun aFileAlreadyThereWholeIsNotFetchedAgain() = runTest {
        val host = FakeHost(twoChapters).apply {
            recordings += recording()
            folder["gopro-x-GX010009.MP4"] = 4_000
        }
        GoProDownload(FakeGoPro(), backgroundScope, host).run("gopro-x")
        assertEquals(listOf("gopro-x-GX020009.MP4"), host.fetched)
        assertTrue(host.recordings.single().downloaded)
    }

    @Test
    fun aDownloadedFileThatIsNoMp4IsDroppedAndItsRecordingStaysToDo() = runTest {
        val host = FakeHost(twoChapters).apply {
            recordings += recording()
            unreadable += "gopro-x-GX020009.MP4"
        }
        val first = GoProDownload(FakeGoPro(), backgroundScope, host).run("gopro-x")
        assertEquals(listOf("gopro-x-GX020009.MP4: not an MP4"), first.problems)
        assertTrue(!host.recordings.single().downloaded)
        assertEquals(setOf("gopro-x-GX010009.MP4"), host.folder.keys, "the damaged file is dropped")

        host.unreadable.clear()
        val second = GoProDownload(FakeGoPro(), backgroundScope, host).run("gopro-x")
        assertEquals(emptyList(), second.problems)
        assertEquals(listOf("gopro-x-GX020009.MP4"), host.fetched.drop(2))
        assertTrue(host.recordings.single().downloaded)
    }

    @Test
    fun downloadsOnlyTheChosenCamerasRecordings() = runTest {
        val host = FakeHost(emptyList()).apply {
            recordings += recording("gopro-a")
            recordings += recording("gopro-b")
        }
        val result = GoProDownload(FakeGoPro(), backgroundScope, host).run("gopro-b")
        assertEquals(listOf("No GoPro files found for the recording started at 2025-10-11T10:53:24Z"), result.problems)
        assertTrue(host.recordings.none { it.downloaded })
    }

    @Test
    fun aCameraThatIsNotFoundOrAWifiThatCannotBeJoinedEndsAsAProblem() = runTest {
        val host = FakeHost(twoChapters).apply { recordings += recording() }
        val gone = FakeGoPro().apply { reachable = false }
        val notFound = GoProDownload(gone, backgroundScope, host).run()
        assertEquals(listOf("GoPro download stopped: not found"), notFound.problems)

        host.failJoin = true
        val camera = FakeGoPro()
        val noWifi = GoProDownload(camera, backgroundScope, host).run()
        assertEquals(listOf("GoPro download stopped: could not join GP12345678"), noWifi.problems)
        // The access point is turned off again all the same.
        assertEquals(listOf(0x17, 0x17), camera.ids)
        assertTrue(!host.recordings.single().downloaded)
    }

    @Test
    fun utcTextIsIso8601ToTheSecond() {
        assertEquals("1970-01-01T00:00:00Z", utcText(0))
        assertEquals("2024-02-29T23:59:59Z", utcText(1_709_251_199_999))
        assertEquals("2026-10-28T12:00:05Z", utcText(1_793_188_805_000))
    }
}
