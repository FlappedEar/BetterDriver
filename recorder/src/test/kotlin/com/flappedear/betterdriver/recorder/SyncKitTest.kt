package com.flappedear.betterdriver.recorder

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.recorder.store.SessionData
import com.flappedear.betterdriver.recorder.store.SessionStore
import com.flappedear.betterdriver.recorder.vbo.AviSyncFormat
import com.flappedear.betterdriver.recorder.vbo.VboExporter
import com.flappedear.betterdriver.recorder.vbo.VboVideo
import com.flappedear.betterdriver.recorder.video.CameraIds
import com.flappedear.betterdriver.recorder.video.VideoClip
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Writes the VBO side of the Circuit Tools sync test pairs (FBBD-51). Skipped unless run with
 * `-PsyncKitDir=<dir>` (tools/sync-kit/make.sh does that, then renders the matching videos).
 *
 * The data is 120 s at 10 Hz on the fake ring. Speed in km/h equals the whole seconds since the
 * session started, so it steps 0, 1, 2 ... 119. Two videos show the session time and that number:
 * file 1 covers 5 s to 65 s, file 2 covers 70 s to 120 s. In Circuit Tools, the speed at the cursor
 * must equal the big number in the video, in both files. Each variant writes avisynctime differently.
 *
 * Round 1 (data from 0 s, avisynctime -1 before the video) played file 1 about 5 s early. Round 2
 * variant E (data starting with the video) lined up, so the exporter now starts the data at the
 * first video and every variant here does that.
 */
class SyncKitTest {
    @Test
    fun `write the sync kit VBO files`() {
        val out = System.getProperty("syncKitDir")
        assumeTrue(!out.isNullOrBlank(), "set -PsyncKitDir to write the kit")
        val root = File(out!!)
        val store = SessionStore(File(root, "work"))
        val data = session(store)
        val clips = listOf(
            VideoClip(CameraIds.PHONE, "a.mp4", FirstVideoSecond * MicrosPerSecond, 65 * MicrosPerSecond),
            VideoClip(CameraIds.PHONE, "b.mp4", 70 * MicrosPerSecond, 120 * MicrosPerSecond),
        )
        val variants = mapOf(
            "A_millis" to VboVideo("SyncA_", clips),
            "C_seconds" to VboVideo("SyncC_", clips, syncFormat = AviSyncFormat.SECONDS),
        )
        for ((name, video) in variants) {
            val dir = File(root, name).apply { mkdirs() }
            File(dir, video.baseName.dropLast(1) + ".vbo").outputStream().use { VboExporter.export(data, it, video) }
            File(dir, "videos.txt").writeText(video.fileNames().joinToString("\n") + "\n")
        }
    }

    private fun session(store: SessionStore): SessionData {
        val gnss = FakeGnss(rateHz = RateHz)
        val info = SessionInfo(gnss.startUtcMillis, track = "Sync test", channels = GnssChannels.ALL)
        val rec = Recorder(store).start(info)
        repeat(Seconds * RateHz) { n ->
            val t = n * MicrosPerSecond / RateHz
            val speed = (n / RateHz).toDouble()
            val values = gnss.fix(n.toLong()).map {
                if (it.channelId == GnssChannels.SPEED_KMH.id) ChannelValue(it.channelId, speed) else it
            }
            rec.submit(SampleSet(t, values))
        }
        rec.stop()
        return store.read(rec.id)
    }

    private companion object {
        const val RateHz = 10
        const val Seconds = 120
        const val FirstVideoSecond = 5
        const val MicrosPerSecond = 1_000_000L
    }
}
