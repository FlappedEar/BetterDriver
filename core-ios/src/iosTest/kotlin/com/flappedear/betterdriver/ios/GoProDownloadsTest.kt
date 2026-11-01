@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.gopro.GoProRecording
import com.flappedear.betterdriver.gopro.GoProRecordingsJson
import com.flappedear.betterdriver.gopro.GoProUuids
import com.flappedear.betterdriver.gopro.OpenGoPro
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoSourceKind
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Downloading an Open GoPro camera's files over its Wi-Fi after the drive on the iPhone (FBBD-343),
 * through the Bluetooth and Wi-Fi bridges the app's Swift code fills in, with a made-up GoPro file
 * riding along the session (DemoGoPro) on the camera.
 */
class GoProDownloadsTest {
    private val base = NSTemporaryDirectory() + "gopro-download-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val gnss = FakeGnss()
    private val files = NSFileManager.defaultManager
    private val camera = "gopro-cam1"

    @AfterTest
    fun cleanUp() {
        files.removeItemAtPath(base, null)
    }

    /** A camera's Bluetooth side: answers every command, and its access point is "GP1234" / "GP1234". */
    private class FakeCameraBle : IosGoProLink() {
        val commands = mutableListOf<Int>()
        private val assembler = OpenGoPro.Assembler()

        override fun beginConnect() = connected(null)

        override fun beginWrite(characteristic: String, packet: NSData) {
            written(null)
            val message = assembler.accept(packet.copyBytes()) ?: return
            commands += message[0].toInt() and 0xFF
            val response = GoProUuids.responseFor(characteristic).uppercase()
            for (p in OpenGoPro.packetize(byteArrayOf(message[0], 0))) notified(response, p.nsData())
        }

        override fun beginRead(characteristic: String) = readDone("GP1234".encodeToByteArray().nsData(), null)

        override fun beginDisconnect() = Unit
    }

    /** A camera's Wi-Fi side serving [media] from the files in [folder]. */
    private inner class FakeCameraWifi(private val folder: String, private val created: Long) : IosGoProWifi() {
        var joinedSsid: String? = null
        var left = false
        var cutShort = false
        val urls = mutableListOf<String>()

        override fun beginJoin(ssid: String, password: String) {
            joinedSsid = ssid
            joined(null)
        }

        override fun beginLeave(ssid: String) {
            left = true
        }

        override fun beginGet(url: String) {
            urls += url
            when {
                url.endsWith("/gopro/camera/keep_alive") -> got("{}", null)
                url.endsWith("/gopro/media/list") -> got(mediaList(), null)
                else -> got(null, "HTTP 404")
            }
        }

        override fun beginDownload(url: String, toPath: String) {
            urls += url
            val name = url.substringAfterLast('/')
            files.removeItemAtPath(toPath, null)
            val from = "$folder/$name"
            if (cutShort) {
                writeBytesAtomically(toPath, ByteArray(10))
            } else {
                files.copyItemAtPath(from, toPath, null)
            }
            val size = sizeOf(from)
            downloadProgress(size, size)
            downloaded(null)
        }

        private fun mediaList(): String {
            val fs = (files.contentsOfDirectoryAtPath(folder, null)?.filterIsInstance<String>().orEmpty())
                .joinToString(",") { """{"n":"$it","cre":"$created","mod":"0","s":"${sizeOf("$folder/$it")}"}""" }
            return """{"id":"1","media":[{"d":"100GOPRO","fs":[$fs]}]}"""
        }
    }

    private fun sizeOf(path: String): Long =
        (files.attributesOfItemAtPath(path, null)?.get(platform.Foundation.NSFileSize) as? platform.Foundation.NSNumber)
            ?.longLongValue ?: 0

    /** Two minutes at 25 Hz away from any saved track, and the camera's note of its start 29 s in. */
    private fun record(): String {
        val session = LiveSession.start(
            sessions, "$base/tracks", CircuitList.Empty,
            SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL), receiverHasG = false,
        )
        for (n in 0L..3000) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        session.stop()
        val note = GoProRecording(
            camera, startAckSessionUs = 29_000_000, startAckUtcMillis = gnss.startUtcMillis + 29_000,
            stopAckSessionUs = 95_000_000, stopAckUtcMillis = gnss.startUtcMillis + 95_000, cameraUtcOffsetMinutes = 0,
        )
        files.createDirectoryAtPath("$sessions/${session.id}/video", true, null, null)
        writeBytesAtomically(
            "$sessions/${session.id}/video/${GoProRecordingsJson.FileName}",
            GoProRecordingsJson.encode(listOf(note)).encodeToByteArray(),
        )
        return session.id
    }

    /** The camera's card: a GoPro file riding along a minute of the session from 30 s. */
    private fun cameraCard(id: String): String {
        val folder = "$base/camera"
        files.createDirectoryAtPath(folder, true, null, null)
        assertNull(DemoGoPro.write(sessions, id, "$folder/GX010042.MP4", 30_000_000, seconds = 60))
        return folder
    }

    private val created get() = (gnss.startUtcMillis + 30_000) / 1000

    @Test
    fun aCamerasFileIsDownloadedOverWifiAndLinedUpByItsGps() = runBlocking {
        val id = record()
        val setup = SettingsCore.addOpenGoPro(
            SettingsCore.freshSetup("phone"), "GoPro 1234", "00000000-0000-0000-0000-000000000001",
            GoProModel.HERO11_BLACK, "cam1",
        )
        assertEquals(
            listOf(CameraOnCamera(camera, "GoPro 1234", "00000000-0000-0000-0000-000000000001")),
            GoProDownloads.onCamera(setup, sessions, id),
        )
        val ble = FakeCameraBle()
        val wifi = FakeCameraWifi(cameraCard(id), created)
        val progress = mutableListOf<GoProDownloadProgress>()

        val problems = GoProDownloads(sessions, "http://camera").run(id, camera, ble, wifi) { progress += it }

        assertEquals(emptyList(), problems)
        assertEquals("GP1234", wifi.joinedSsid)
        assertTrue(wifi.left)
        assertTrue("http://camera/videos/DCIM/100GOPRO/GX010042.MP4" in wifi.urls, "${wifi.urls}")
        // Access point on, then off at the end.
        assertEquals(listOf(0x17, 0x17), ble.commands)
        assertEquals(1, progress.last().index)
        val clip = ClipIndex("$sessions/$id/video").clips().single()
        assertEquals("gopro-cam1-GX010042.MP4", clip.fileName)
        assertEquals(VideoSourceKind.GOPRO, clip.kind)
        // GPS UTC on both sides (the made-up file carries GPS9 times): 30 s into the session, as recorded.
        assertEquals(AlignmentMethod.GPS_UTC, clip.alignment.method)
        assertEquals(30_000_000L, clip.sessionOffsetUs)
        val left = files.contentsOfDirectoryAtPath("$sessions/$id/video", null)?.filterIsInstance<String>().orEmpty()
        assertTrue(left.none { it.endsWith(".part") }, "$left")
        // Done: the card offers Download no more, and the line says the clip is lined up.
        assertEquals(emptyList(), GoProDownloads.onCamera(setup, sessions, id))
        assertEquals("GoPro 1234 · 1 clip lined up", GoProCore.lines(setup, sessions, id).single().text)
    }

    @Test
    fun aCutOffDownloadLeavesNothingInTheSessionAndStaysToDo() = runBlocking {
        val id = record()
        val wifi = FakeCameraWifi(cameraCard(id), created).apply { cutShort = true }

        val problems = GoProDownloads(sessions, "http://camera").run(id, camera, FakeCameraBle(), wifi) {}

        assertEquals(1, problems.size, "$problems")
        assertTrue(problems.single().startsWith("GoPro download stopped: GX010042.MP4: got 10 of"), problems.single())
        assertTrue(wifi.left)
        val left = files.contentsOfDirectoryAtPath("$sessions/$id/video", null)?.filterIsInstance<String>().orEmpty()
        assertEquals(listOf(GoProRecordingsJson.FileName), left)
        val still = GoProDownloads.onCamera(SettingsCore.freshSetup("phone"), sessions, id)
        assertEquals(listOf(camera), still.map { it.sourceId })
    }

    @Test
    fun aCameraWhoseWifiNeverAnswersEndsAsAProblem() = runBlocking {
        val id = record()
        val wifi = object : IosGoProWifi() {
            override fun beginJoin(ssid: String, password: String) = joined("The GoPro's Wi-Fi was not found")
            override fun beginLeave(ssid: String) = Unit
            override fun beginGet(url: String) = got(null, "offline")
            override fun beginDownload(url: String, toPath: String) = downloaded("offline")
        }
        val ble = FakeCameraBle()
        val problems = GoProDownloads(sessions, "http://camera").run(id, camera, ble, wifi) {}
        assertEquals(listOf("GoPro download stopped: The GoPro's Wi-Fi was not found"), problems)
        assertEquals(listOf(0x17, 0x17), ble.commands)
    }
}

private fun ByteArray.nsData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

private fun NSData.copyBytes(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
