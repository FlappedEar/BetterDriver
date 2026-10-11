package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.AviSyncFormat
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import com.flappedear.betterdriver.recorder.video.VideoEventText
import com.flappedear.betterdriver.recorder.video.VideoFiles
import com.flappedear.betterdriver.recorder.video.VideoTimeline
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoTest {
    @TempDir
    lateinit var tmp: File

    private val gnss = FakeGnss()

    private fun ev(t: Long, kind: String, file: String, extra: String = "") =
        RecorderEvent(t, kind, "camera=phone file=$file$extra")

    @Test
    fun `clip start is the earliest sync estimate, not the late start event`() {
        val events = listOf(
            ev(1_050_000, EventKinds.VIDEO_START, "phone_0001.mp4"),
            ev(2_030_000, EventKinds.VIDEO_SYNC, "phone_0001.mp4", " media_us=1000000"),
            ev(3_010_000, EventKinds.VIDEO_SYNC, "phone_0001.mp4", " media_us=2000000"),
            ev(4_090_000, EventKinds.VIDEO_SYNC, "phone_0001.mp4", " media_us=3000000"),
            ev(9_000_000, EventKinds.VIDEO_STOP, "phone_0001.mp4", " reason=user"),
        )
        val clip = VideoTimeline.from(events).clips.single()
        assertEquals(1_010_000, clip.startMicros)
        assertEquals(9_000_000, clip.endMicros)
    }

    @Test
    fun `a clip with no stop ends at its last sync, and one with nothing but a start runs on`() {
        val events = listOf(
            ev(1_000_000, EventKinds.VIDEO_START, "phone_0001.mp4"),
            ev(5_000_000, EventKinds.VIDEO_SYNC, "phone_0001.mp4", " media_us=4000000"),
            ev(20_000_000, EventKinds.VIDEO_START, "phone_0002.mp4"),
        )
        val t = VideoTimeline.from(events)
        assertEquals(listOf(5_000_000L, null), t.clips.map { it.endMicros })
        assertNull(t.locate(CameraIds.PHONE, 6_000_000))
        val pos = t.locate(CameraIds.PHONE, 25_000_000)!!
        assertEquals(1, pos.index)
        assertEquals(5_000_000, pos.mediaMicros)
        assertNull(t.locate(CameraIds.PHONE, 500_000))
    }

    @Test
    fun `cameras are kept apart and indexed clips win over events`() {
        val indexed = listOf(
            VideoClip(CameraIds.GOPRO_HERO8, "GX010123.MP4", -2_000_000, 600_000_000),
            VideoClip(CameraIds.PHONE, "phone_0001.mp4", 40_000, 9_000_000),
        )
        val events = listOf(
            ev(0, EventKinds.VIDEO_START, "phone_0001.mp4"),
            ev(10_000_000, EventKinds.VIDEO_START, "phone_0002.mp4"),
        )
        val t = VideoTimeline.from(events, indexed)
        assertEquals(listOf(CameraIds.GOPRO_HERO8, CameraIds.PHONE), t.cameras)
        assertEquals(3_000_000, t.locate(CameraIds.GOPRO_HERO8, 1_000_000)!!.mediaMicros)
        assertEquals(listOf(40_000L, 10_000_000L), t.clips(CameraIds.PHONE).map { it.startMicros })
    }

    @Test
    fun `event text keeps values on one token`() {
        val text = VideoEventText.format("camera" to "phone", "reason" to "too hot")
        assertEquals("camera=phone reason=too_hot", text)
        assertEquals(mapOf("camera" to "phone", "reason" to "too_hot"), VideoEventText.parse(text))
        assertEquals("phone_0012.mp4", VideoFiles.liveName("phone", 12))
    }

    @Test
    fun `camera reports stamped in another process land at their session time`() {
        val store = SessionStore(tmp)
        var now = 1_000_000_000L
        val info = SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL)
        val rec = Recorder(store, nanoTime = { now }).start(info)
        val sink = RecordingVideoSink(rec, CameraIds.PHONE)
        now += 5_000_000_000L
        // Stamped 2 s before the recorder logs it, as a report from the camera process would be.
        sink.started("phone_0001.mp4", (now - 2_000_000_000L - rec.startNanos) / 1_000)
        sink.synced("phone_0001.mp4", rec.nowMicros(), 2_000_000)
        sink.stopped("phone_0001.mp4", rec.nowMicros(), "user")
        rec.stop()
        val data = store.read(rec.id)
        val clip = VideoTimeline.from(data.events).clips.single()
        assertEquals(3_000_000, clip.startMicros)
        assertEquals(5_000_000, clip.endMicros)
    }

    @Test
    fun `export with video starts at the video and writes a file index and time on every row`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        // 10 fixes at 25 Hz: 0 to 360 ms.
        repeat(10) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        rec.stop()
        val clips = listOf(
            VideoClip(CameraIds.PHONE, "phone_0001.mp4", 100_000, 200_000),
            VideoClip(CameraIds.PHONE, "phone_0002.mp4", 280_000, null),
        )
        val video = VboVideo("BetterDriver-x_", clips)
        assertEquals(listOf("BetterDriver-x_0001.MP4", "BetterDriver-x_0002.MP4"), video.fileNames())

        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out, video)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals(listOf("BetterDriver-x_", "MP4"), p.body["[avi]"])
        assertEquals(listOf("avifileindex", "avisynctime"), p.columnNames.takeLast(2))
        assertEquals(listOf("avifileindex", "avisynctime"), p.header.takeLast(2))
        val avi = p.rows.map { it.takeLast(2) }
        assertEquals(
            listOf(
                // 0, 40 and 80 ms are before the first file: left out, so the data starts with the video.
                listOf("0001", "000000020"), // 120
                listOf("0001", "000000060"), // 160
                listOf("0001", "000000100"), // 200: last frame of file 1
                listOf("0002", "-00000001"), // 240: gap
                listOf("0002", "000000000"), // 280
                listOf("0002", "000000040"), // 320
                listOf("0002", "000000080"), // 360
            ),
            avi,
        )
    }

    @Test
    fun `seconds variant for the Circuit Tools test pairs`() {
        val store = SessionStore(tmp)
        val rec = Recorder(store).start(SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL))
        repeat(3) { rec.submit(SampleSet(it * 40_000L, gnss.fix(it.toLong()))) }
        rec.stop()
        val video = VboVideo(
            "s_",
            listOf(VideoClip(CameraIds.PHONE, "a.mp4", 40_000, null)),
            syncFormat = AviSyncFormat.SECONDS,
            extensionLine = false,
        )
        val out = ByteArrayOutputStream()
        VboExporter.export(store.read(rec.id), out, video)
        val p = VboValidator.validate(out.toString(Charsets.UTF_8))
        assertEquals(listOf("s_"), p.body["[avi]"])
        assertEquals(listOf("0.000", "0.040"), p.rows.map { it.last() })
    }
}
