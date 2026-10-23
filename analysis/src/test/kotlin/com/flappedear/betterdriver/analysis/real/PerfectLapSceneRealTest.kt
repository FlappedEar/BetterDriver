@file:Suppress("MagicNumber") // Arek's lap times and the size of a picture.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.analysis.line.SceneGround
import com.flappedear.betterdriver.analysis.line.ScenePaint
import com.flappedear.betterdriver.analysis.line.analyzeLine
import com.flappedear.betterdriver.analysis.line.linePlan
import com.flappedear.betterdriver.analysis.line.perfectLapScene
import com.flappedear.betterdriver.analysis.line.sceneFrame
import com.flappedear.betterdriver.analysis.recorded.telemetrySessionOf
import com.flappedear.betterdriver.recorder.store.SessionStore
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The perfect lap in 3D (FBBD-308) on Arek's six Jastrząb sessions of 29 Aug 2026: the line plan
 * of Arek's 16:06 session (best lap 1:49.91) driven as the perfect lap, with the best lap as a ghost
 * car. Every frame of the lap is drawn as the player and the video draw it, and must show both.
 */
class PerfectLapSceneRealTest {
    @TempDir
    lateinit var tmp: File

    private val track = RealCorpus.jastrzabTrack()

    private fun plan(): LinePlan {
        val store = SessionStore(File(tmp, "sessions"))
        val sessions = RealCorpus.JASTRZAB_DAY.map { name ->
            val id = RealCorpus.import(store, File(tmp, "staging"), name, listOf(track)).id
            name.substringAfter("2026-08-29-").take(4) to telemetrySessionOf(store.read(id), track)
        }
        val session = sessions.single { it.first == "1606" }.second
        val others = sessions.filter { it.first != "1606" }
            .map { (time, s) -> LineSession(s, deriveSourceLapSession(s), time, "s$time") }
        val analysis = analyzeSession(session)
        val line = assertNotNull(analyzeLine(analysis, session, others))
        return linePlan(line, analysis.axis!!.origin, "jastrzab", "s1606", 1L, identityRevision = null)
    }

    @Test
    fun `the perfect lap takes the plan's time and the best lap drops back by the time to find`() {
        val plan = plan()
        val scene = assertNotNull(perfectLapScene(plan))
        assertEquals(plan.perfectLap().seconds, scene.perfectSeconds, 1e-9)
        assertEquals(plan.perfectLap().seconds, scene.durationSeconds, 1e-6)
        // The best lap through the same windows is the best lap.
        assertEquals(plan.referenceLapSeconds, scene.bestSeconds, 0.3)
        assertEquals(plan.corners.size, scene.corners.size)
        // Each corner takes the best lap's time there less the time to find, one after the other.
        plan.corners.zip(scene.corners).forEach { (c, s) ->
            assertEquals(c.reference.seconds - c.gainSeconds, s.endSeconds - s.startSeconds, 1e-6, c.name)
        }
        // At the end of the lap the ghost is behind by the whole time to find.
        assertEquals(plan.totalGainSeconds, scene.gapAt(scene.durationSeconds), 0.02)
        // Times run forward along both lines.
        for (line in listOf(scene.perfect, scene.best)) {
            line.zipWithNext().forEach { (a, b) -> assertTrue(b.seconds >= a.seconds && b.lapMeters > a.lapMeters) }
        }
        // The two cars start together and stay close: never more than a few car lengths apart.
        val worst = (0..(scene.durationSeconds * 10).toInt()).maxOf { i ->
            val t = i / 10.0
            val a = scene.perfectAt(t)
            val b = scene.bestAt(t)
            kotlin.math.hypot(a.eastMeters - b.eastMeters, a.northMeters - b.northMeters)
        }
        assertTrue(worst < 40.0, "cars $worst m apart")
    }

    @Test
    fun `every frame shows the perfect lap's car on the road, from above its corner`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        val ground = SceneGround(scene)
        for ((w, h) in listOf(1920 to 1080, 1000 to 1000)) {
            var ghostShown = 0
            val frames = (scene.durationSeconds * 10).toInt()
            for (i in 0..frames) {
                val t = i / 10.0
                val f = sceneFrame(scene, ground, t, w, h)
                val car = f.shapes.filter { it.paint == ScenePaint.Car }
                assertTrue(car.isNotEmpty(), "no car at $t s in $w×$h")
                // Fully in the picture, with room round it.
                car.forEach { s ->
                    val inside = s.xs.all { it in 0f..w.toFloat() } && s.ys.all { it in 0f..h.toFloat() }
                    assertTrue(inside, "car off the picture at $t s")
                }
                assertTrue(f.shapes.count { it.paint == ScenePaint.Road } > 20, "road at $t s")
                if (f.shapes.any { it.paint == ScenePaint.Ghost }) ghostShown++
            }
            // The ghost is beside the perfect lap's car nearly all the time.
            assertTrue(ghostShown >= frames * 0.95, "ghost in $ghostShown of $frames frames")
        }
        writeFrames(scene, ground)
    }

    @Test
    fun `the camera looks down at each corner and moves smoothly to the next`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        var last = scene.cameraAt(0.0, 16.0 / 9)
        for (i in 1..(scene.durationSeconds * 30).toInt()) {
            val c = scene.cameraAt(i / 30.0, 16.0 / 9)
            val d = c.eye - c.target
            // Looking down, steeply.
            assertTrue(d.z / d.length > sin(Math.toRadians(45.0)), "camera at ${i / 30.0} s")
            // No jump between two frames of a 30 fps video.
            assertTrue((c.target - last.target).length < 15.0, "camera jumps at ${i / 30.0} s")
            last = c
        }
        scene.corners.indices.forEach { assertTrue(scene.cornerCamera(it, 16.0 / 9).eye.z in 60.0..400.0) }
        assertTrue(cos(Math.toRadians(PerfectLapScene.CameraTiltDegrees)) > 0)
    }

    /** A few frames as SVG under build/tmp for a look; not uploaded with the reports. */
    private fun writeFrames(scene: PerfectLapScene, ground: SceneGround) {
        val dir = File("build/tmp/perfect-lap-3d").apply { mkdirs() }
        val colors = mapOf(
            ScenePaint.Road to "#2b3136", ScenePaint.RoadEdge to "#cfd6db", ScenePaint.Braking to "#FF4B4B",
            ScenePaint.Coasting to "#9aa3ab", ScenePaint.Accelerating to "#22D37A", ScenePaint.NoPedal to "#9aa3ab",
            ScenePaint.Car to "#FCB203", ScenePaint.CarDark to "#3a2c00", ScenePaint.Ghost to "#ffffff",
            ScenePaint.CarHalo to "#FCB203", ScenePaint.GhostHalo to "#ffffff",
        )
        for (t in listOf(5.0, scene.durationSeconds * 0.5, scene.durationSeconds - 8)) {
            val f = sceneFrame(scene, ground, t, 1280, 720)
            val body = f.shapes.joinToString("") { s ->
                val pts = s.xs.indices.joinToString(" ") { String.format(Locale.ROOT, "%.1f,%.1f", s.xs[it], s.ys[it]) }
                val op = when (s.paint) {
                    ScenePaint.Ghost -> " fill-opacity=\"0.4\""
                    ScenePaint.CarHalo, ScenePaint.GhostHalo -> " fill-opacity=\"0.3\""
                    else -> ""
                }
                "<polygon points=\"$pts\" fill=\"${colors[s.paint]}\"$op/>"
            }
            File(dir, String.format(Locale.ROOT, "frame-%05.1f.svg", t)).writeText(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">" +
                    "<rect width=\"1280\" height=\"720\" fill=\"#17261b\"/>$body</svg>",
            )
        }
        assertTrue(abs(scene.gapAt(0.0)) < 0.05)
    }
}
