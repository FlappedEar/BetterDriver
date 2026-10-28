@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.flappedear.betterdriver.ios

import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.recorder.SessionInfo
import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.setup.GoProModel
import com.flappedear.betterdriver.timing.circuits.CircuitList
import com.flappedear.betterdriver.video.AlignmentMethod
import com.flappedear.betterdriver.video.VideoSourceKind
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Adding a GoPro's files to a session on the iPhone (FBBD-343), lined up by the GoPro's GPS as on Android. */
class GoProImportTest {
    private val base = NSTemporaryDirectory() + "gopro-import-test-" + NSUUID().UUIDString
    private val sessions = "$base/sessions"
    private val gnss = FakeGnss()
    private val files = NSFileManager.defaultManager
    private val setup = SettingsCore.addGoProFiles(SettingsCore.freshSetup("phone"), GoProModel.HERO8_BLACK, "cam1")
    private val camera = "gopro-cam1"

    @AfterTest
    fun cleanUp() {
        files.removeItemAtPath(base, null)
    }

    /** Two minutes at 25 Hz away from any saved track. */
    private fun record(): String {
        val session = LiveSession.start(
            sessions, "$base/tracks", CircuitList.Empty,
            SessionInfo(gnss.startUtcMillis, channels = GnssChannels.ALL), receiverHasG = false,
        )
        for (n in 0L..3000) session.submit(SampleSet(n * 40_000, gnss.fix(n)))
        session.stop()
        return session.id
    }

    private fun goProFile(id: String, name: String, utcShiftMillis: Long = 0): String {
        files.createDirectoryAtPath("$base/picked", true, null, null)
        val path = "$base/picked/$name"
        val written = DemoGoPro.write(sessions, id, path, 30_000_000, seconds = 60, utcShiftMillis = utcShiftMillis)
        assertTrue(written)
        return path
    }

    private fun videoFiles(id: String): List<String> =
        files.contentsOfDirectoryAtPath("$sessions/$id/video", null)?.filterIsInstance<String>().orEmpty()

    @Test
    fun aGoProFileIsLinedUpByItsGpsAndAddedToTheSession() {
        val id = record()
        assertEquals(listOf(camera), GoProCore.addable(setup, sessions, id).map { it.sourceId })
        val copied = mutableListOf<Long>()
        val problems = GoProImport(sessions).run(
            id, camera, GoProCore.kindFor(setup, camera),
            listOf(PickedCameraFile(goProFile(id, "GH010042.MP4"), "GH010042.MP4")),
        ) { copied += it }
        assertEquals(emptyList(), problems)
        assertTrue(copied.isNotEmpty())

        val clips = ClipIndex("$sessions/$id/video").clips()
        assertEquals(1, clips.size)
        val clip = clips.single()
        assertEquals("gopro-cam1-GH010042.MP4", clip.fileName)
        assertEquals(VideoSourceKind.GOPRO_FILES, clip.kind)
        assertEquals(camera, clip.sourceId)
        // GPS UTC on both sides: the file starts 30 s into the session, to the millisecond.
        assertEquals(AlignmentMethod.GPS_UTC, clip.alignment.method)
        assertEquals(30_000_000L, clip.sessionOffsetUs)
        assertEquals(60_000_000L, clip.durationUs)
        // The file is in the session's video folder, and the staging folder is gone.
        assertEquals(setOf("clips.json", "gopro-cam1-GH010042.MP4"), videoFiles(id).toSet())

        val line = GoProCore.lines(setup, sessions, id).single()
        assertEquals("GoPro HERO8 Black · 1 clip lined up", line.text)
        assertEquals("by GPS ±1 s", line.how)
        assertTrue(line.byGps)
        assertEquals(emptyList(), GoProCore.addable(setup, sessions, id))
    }

    @Test
    fun aFileFromAnotherDayIsReportedAndNotAdded() {
        val id = record()
        val threeDays = 3L * 24 * 3600 * 1000
        val problems = GoProImport(sessions).run(
            id, camera, VideoSourceKind.GOPRO_FILES,
            listOf(PickedCameraFile(goProFile(id, "GH010043.MP4", utcShiftMillis = threeDays), "GH010043.MP4")),
        ) {}
        assertEquals(listOf("gopro-cam1-GH010043.MP4: recorded outside this session"), problems)
        assertEquals(emptyList(), ClipIndex("$sessions/$id/video").clips())
        assertTrue(videoFiles(id).none { it.endsWith(".MP4") || it.startsWith(".import-") }, videoFiles(id).toString())
    }

    @Test
    fun aFileThatIsNotAVideoIsReported() {
        val id = record()
        files.createDirectoryAtPath("$base/picked", true, null, null)
        val path = "$base/picked/notes.txt"
        writeBytesAtomically(path, "not a video".encodeToByteArray())
        val picked = listOf(PickedCameraFile(path, "notes.txt"))
        val problems = GoProImport(sessions).run(id, camera, VideoSourceKind.GOPRO_FILES, picked) {}
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("gopro-cam1_0001.MP4: "), problems.single())
        assertEquals(emptyList(), ClipIndex("$sessions/$id/video").clips())
    }

    @Test
    fun alignmentTextIsAndroids() {
        val gps = com.flappedear.betterdriver.video.Alignment(AlignmentMethod.GPS_MATCH, 50_000)
        assertEquals("by GPS ±0.05 s", alignmentText(gps))
        val clock = com.flappedear.betterdriver.video.Alignment(AlignmentMethod.CAMERA_CLOCK, 30L * 60 * 1_000_000)
        assertEquals("by camera clock ±1800 s", alignmentText(clock))
    }
}
