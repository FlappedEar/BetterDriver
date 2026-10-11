package com.flappedear.betterdriver.gopro

import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoIndex
import com.flappedear.betterdriver.video.gpmf.FakeGoProFile
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoProDownloaderTest {
    @TempDir
    lateinit var dir: File

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    /** Serves a camera's HTTP API with one HERO11 recording in two chapters. */
    private fun serve(files: Map<String, ByteArray>, createdSeconds: Long): String {
        val list = files.entries.joinToString(",") { (name, body) ->
            """{"n":"$name","cre":"$createdSeconds","mod":"0","s":"${body.size}"}"""
        }
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/gopro/media/list") { ex ->
            val body = """{"id":"1","media":[{"d":"100GOPRO","fs":[$list]}]}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.createContext("/videos/DCIM/100GOPRO/") { ex ->
            val body = files[ex.requestURI.path.substringAfterLast('/')]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}"
    }

    private fun hero11Chapter(offsetUs: Long, seconds: Int): ByteArray {
        val tmp = File.createTempFile("gopro", ".mp4", dir)
        FakeGoProFile.writeMp4(tmp, List(seconds) { s ->
            val first = TestSession.StartUtcMillis + offsetUs / 1000 + s * 1000L
            FakeGoProFile.gps9Payload(first, 100, List(10) { i -> TestSession.speedAt(first + i * 100L) })
        })
        return tmp.readBytes().also { tmp.delete() }
    }

    @Test
    fun `downloads the session's files over Wi-Fi, aligns them and turns the AP off`() = runTest {
        val sessionDir = File(dir, "session").apply { mkdirs() }
        val videoDir = File(sessionDir, "video")
        // The camera acknowledged start 4.0 s into the session; its first frame is at 4.6 s.
        GoProRecordings(videoDir).put(GoProRecording("gopro-x", startAckSessionUs = 4_000_000,
            startAckUtcMillis = TestSession.StartUtcMillis + 4_000, stopAckSessionUs = 200_000_000,
            stopAckUtcMillis = TestSession.StartUtcMillis + 200_000, cameraUtcOffsetMinutes = 0))
        val files = mapOf(
            "GX010009.MP4" to hero11Chapter(4_600_000, 100),
            "GX020009.MP4" to hero11Chapter(104_600_000, 90),
        )
        val base = serve(files, TestSession.StartUtcMillis / 1000 + 5)
        val camera = FakeGoPro()
        var joined: Pair<String, String>? = null
        var left = false
        val wifi = object : WifiJoiner {
            override suspend fun join(ssid: String, password: String): GoProHttp {
                joined = ssid to password
                return GoProHttp(base)
            }
            override suspend fun leave() {
                left = true
            }
        }
        val result = GoProDownloader(camera, backgroundScope, wifi).download(sessionDir, TestSession.data(300))
        assertEquals(emptyList(), result.problems)
        assertEquals(listOf("GX010009.MP4", "GX020009.MP4"), result.downloaded)
        assertEquals("GP12345678" to "secret-pass", joined)
        assertTrue(left)
        // AP on, then AP off at the end.
        assertEquals(listOf(0x17, 0x17), camera.ids)
        assertEquals(0, camera.requests.last().second[2].toInt())
        val clips = VideoIndex(sessionDir).clips()
        assertEquals(2, clips.size)
        assertTrue(clips.all { it.alignment.method == AlignmentMethod.GPS_MATCH })
        assertTrue(abs(clips[0].sessionOffsetUs - 4_600_000) <= 20_000, "${clips[0].sessionOffsetUs}")
        assertTrue(GoProRecordings(videoDir).all().single().downloaded)
        // Saved under the camera's id, so two cameras' GX010009.MP4 never meet.
        assertTrue(File(videoDir, "gopro-x-GX020009.MP4").length() == files["GX020009.MP4"]!!.size.toLong())

        // A second run has nothing left to do.
        val again = GoProDownloader(camera, backgroundScope, wifi).download(sessionDir, TestSession.data(300))
        assertEquals(emptyList(), again.downloaded)
    }

    @Test
    fun `downloads only the chosen camera's recordings`() = runTest {
        val sessionDir = File(dir, "session3").apply { mkdirs() }
        val store = GoProRecordings(File(sessionDir, "video"))
        store.put(GoProRecording("gopro-a", startAckSessionUs = 0, startAckUtcMillis = TestSession.StartUtcMillis))
        store.put(GoProRecording("gopro-b", startAckSessionUs = 0, startAckUtcMillis = TestSession.StartUtcMillis))
        val wifi = object : WifiJoiner {
            override suspend fun join(ssid: String, password: String): GoProHttp = GoProHttp(serve(emptyMap(), 0))
            override suspend fun leave() = Unit
        }
        val result = GoProDownloader(FakeGoPro(), backgroundScope, wifi)
            .download(sessionDir, TestSession.data(10), sourceId = "gopro-b")
        assertEquals(1, result.problems.size, "only camera b's recording was looked for")
        assertEquals("x.MP4", GoProDownloader.localName("gopro_hero11", "x.MP4"), "older sessions keep the names")
        assertEquals("gopro-b-x.MP4", GoProDownloader.localName("gopro-b", "x.MP4"))
    }

    @Test
    fun `a failed join is reported and the AP is still turned off`() = runTest {
        val sessionDir = File(dir, "session2").apply { mkdirs() }
        GoProRecordings(File(sessionDir, "video")).put(GoProRecording("g", startAckSessionUs = 0,
            startAckUtcMillis = TestSession.StartUtcMillis))
        val camera = FakeGoPro()
        val wifi = object : WifiJoiner {
            override suspend fun join(ssid: String, password: String): GoProHttp =
                throw java.io.IOException("no network")
            override suspend fun leave() = Unit
        }
        val result = GoProDownloader(camera, backgroundScope, wifi).download(sessionDir, TestSession.data(10))
        assertEquals(listOf("GoPro download stopped: no network"), result.problems)
        assertEquals(listOf(0x17, 0x17), camera.ids)
        assertTrue(!GoProRecordings(File(sessionDir, "video")).all().single().downloaded)
    }

    @Test
    fun `download refuses a truncated file`() {
        val base = serve(mapOf("GX010001.MP4" to ByteArray(10)), 0)
        val http = GoProHttp(base)
        val file = MediaFile("100GOPRO", "GX010001.MP4", 0, 20)
        val target = File(dir, "GX010001.MP4")
        runCatching { http.download(file, target) }.also { assertTrue(it.isFailure) }
        assertTrue(!target.exists())
    }
}
