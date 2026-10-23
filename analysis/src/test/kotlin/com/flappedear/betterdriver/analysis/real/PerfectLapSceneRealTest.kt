@file:Suppress("MagicNumber") // Arek's lap times and the size of a picture.

package com.flappedear.betterdriver.analysis.real

import com.flappedear.betterdriver.analysis.analyzeSession
import com.flappedear.betterdriver.analysis.laps.deriveSourceLapSession
import com.flappedear.betterdriver.analysis.line.LinePlan
import com.flappedear.betterdriver.analysis.line.LineSession
import com.flappedear.betterdriver.analysis.line.PerfectLapScene
import com.flappedear.betterdriver.analysis.line.SceneFrame
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
    fun `every frame follows the perfect lap's car from behind, with its line and the map under the road`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        val ground = SceneGround(scene)
        for ((w, h) in listOf(1920 to 1080, 1280 to 720)) {
            var ghostShown = 0
            val frames = (scene.durationSeconds * 10).toInt()
            for (i in 0..frames) {
                val t = i / 10.0
                val f = sceneFrame(scene, ground, t, w, h)
                val car = f.shapes.filter { it.paint == ScenePaint.Car }
                assertTrue(car.isNotEmpty(), "no car at $t s in $w×$h")
                // Fully in the picture, below its middle, and big enough to see (UI-200 A).
                val xs = car.flatMap { it.xs.asList() }
                val ys = car.flatMap { it.ys.asList() }
                val inside = xs.all { it in 0f..w.toFloat() } && ys.all { it in 0f..h.toFloat() }
                assertTrue(inside, "car off the picture at $t s")
                assertTrue(ys.min() > h / 2f, "car above the middle at $t s")
                assertTrue(xs.max() - xs.min() > w / 60f, "car ${xs.max() - xs.min()} px wide at $t s")
                assertTrue(f.shapes.count { it.paint == ScenePaint.Road } > 20, "road at $t s")
                val line = listOf(ScenePaint.Braking, ScenePaint.Coasting, ScenePaint.Accelerating, ScenePaint.NoPedal)
                assertTrue(f.shapes.count { it.paint in line } > 20, "line at $t s")
                // Map tiles under the whole bottom half, where the car is.
                assertTrue(f.tiles.isNotEmpty(), "no map at $t s")
                assertTrue(f.tiles.all { (it.z == 18 || it.z == 16) && it.u1 > it.u0 && it.v1 > it.v0 })
                if (f.shapes.any { it.paint == ScenePaint.Ghost }) ghostShown++
            }
            // The ghost is in the picture nearly all the time: it drops back by at most the time to find.
            assertTrue(ghostShown >= frames * 0.9, "ghost in $ghostShown of $frames frames")
        }
        writeFrames(scene, ground)
    }

    @Test
    fun `the map tiles cover the road and the car's place is inside one of them`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        val ground = SceneGround(scene)
        // Jastrząb is about 1 km across: a few dozen tiles of about 100 m at zoom 18.
        val near = ground.tiles.filter { (id, _) -> id[0] == 18 }
        val far = ground.tiles.filter { (id, _) -> id[0] == 16 }
        assertEquals(ground.tiles.size, near.size + far.size)
        assertTrue(near.size in 20..400, "${near.size} tiles")
        // The coarse map is drawn first, under the fine one.
        val lastFar = ground.tiles.indexOfLast { (id, _) -> id[0] == 16 }
        assertTrue(lastFar < ground.tiles.indexOfFirst { (id, _) -> id[0] == 18 })
        for (tiles in listOf(near, far)) for (p in scene.perfect.filterIndexed { i, _ -> i % 25 == 0 }) {
            val inside = tiles.count { (_, q) ->
                val minE = minOf(q[0], q[9])
                val maxE = maxOf(q[3], q[6])
                val minN = minOf(q[7], q[10])
                val maxN = maxOf(q[1], q[4])
                p.eastMeters in minE..maxE && p.northMeters in minN..maxN
            }
            assertEquals(1, inside, "tiles over (${p.eastMeters}, ${p.northMeters})")
        }
        // A zoom-18 tile at 50° north is about 98 m a side, a zoom-16 one four times that.
        val (_, q) = near.first()
        assertEquals(98.0, kotlin.math.hypot(q[3] - q[0], q[4] - q[1]), 4.0)
        val (_, qf) = far.first()
        assertEquals(393.0, kotlin.math.hypot(qf[3] - qf[0], qf[4] - qf[1]), 16.0)
    }

    @Test
    fun `the map photo fills the whole picture at every camera distance`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        val ground = SceneGround(scene)
        val (w, h) = 1920 to 1080
        var maxTiles = 0
        for (distance in listOf(PerfectLapScene.MinCameraDistance, 1.0, PerfectLapScene.MaxCameraDistance)) {
            for (i in 0..(scene.durationSeconds * 2).toInt()) {
                val t = i / 2.0
                val f = sceneFrame(scene, ground, t, w, h, distance)
                // Few enough different tiles for the phone to keep them all in memory while it plays.
                maxTiles = maxOf(maxTiles, f.tiles.map { Triple(it.z, it.x, it.y) }.toSet().size)
                // Every point of a grid over the picture, edges included, is on a map piece.
                val hole = uncovered(f, w, h)
                assertEquals(null, hole, "no map at $hole, $t s, distance $distance")
                // The car's own place has a fine piece once those tiles are loaded.
                val car = f.shapes.first { it.paint == ScenePaint.Car }
                val cx = car.xs.average().toFloat()
                val cy = car.ys.average().toFloat()
                val fine = f.tiles.any { it.z == 18 && inside(it.xs, it.ys, cx, cy) }
                assertTrue(fine, "no fine map under the car at $t s")
            }
        }
        assertTrue(maxTiles <= 48, "$maxTiles tiles in one picture")
    }

    /** A point of a 17 × 10 grid over the picture that no map piece covers, or null. */
    private fun uncovered(f: SceneFrame, w: Int, h: Int): Pair<Float, Float>? {
        val grid = (0..16).flatMap { gx -> (0..9).map { gy -> gx * (w - 1) / 16f to gy * (h - 1) / 9f } }
        return grid.firstOrNull { (x, y) -> f.tiles.none { inside(it.xs, it.ys, x, y) } }
    }

    private fun inside(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Boolean {
        var c = false
        var j = xs.size - 1
        for (i in xs.indices) {
            if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) c = !c
            j = i
        }
        return c
    }

    @Test
    fun `brake and gas bars stand where the perfect lap's pedals change, one of each per braking zone`() {
        val plan = plan()
        val scene = assertNotNull(perfectLapScene(plan))
        val ground = SceneGround(scene)
        val brakes = ground.bars.filter { it.paint == ScenePaint.BrakeBar }
        val gas = ground.bars.filter { it.paint == ScenePaint.GasBar }
        // Every corner of the plan brakes and goes back on the gas at least once, and not a bar every few metres.
        val range = scene.corners.size / 2..scene.corners.size * 3
        assertTrue(brakes.size in range, "${brakes.size} brake bars")
        assertTrue(gas.size in range, "${gas.size} gas bars")
        assertTrue(brakes.all { it.text == "BRAKE" } && gas.all { it.text == "GAS" })
        // A word is shown in the picture as its bar comes up.
        val words = (0..(scene.durationSeconds * 2).toInt()).sumOf { i ->
            sceneFrame(scene, ground, i / 2.0, 1920, 1080).labels.count { it.size >= 12f }
        }
        assertTrue(words > brakes.size, "$words words")
    }

    @Test
    fun `the camera stays behind and above the car, looking down, and moves smoothly`() {
        val scene = assertNotNull(perfectLapScene(plan()))
        var last = scene.cameraAt(0.0)
        for (i in 1..(scene.durationSeconds * 30).toInt()) {
            val t = i / 30.0
            val c = scene.cameraAt(t)
            val car = scene.perfectAt(t)
            val d = c.eye - c.target
            // Looking down between 15° and 40°, from 22 m up and behind the car.
            val down = Math.toDegrees(kotlin.math.asin(d.z / d.length))
            assertTrue(down in 15.0..40.0, "camera looks down $down° at $t s")
            assertEquals(PerfectLapScene.CameraUpMeters, c.eye.z, 1e-9)
            val back = kotlin.math.hypot(c.eye.x - car.eastMeters, c.eye.y - car.northMeters)
            assertEquals(PerfectLapScene.CameraBehindMeters, back, 1e-6)
            // No jump between two frames of a 30 fps video.
            assertTrue((c.eye - last.eye).length < 4.0, "camera jumps ${(c.eye - last.eye).length} m at $t s")
            last = c
        }
        assertTrue(cos(Math.toRadians(PerfectLapScene.CameraFovDegrees)) > 0)
        // Pinched nearer or farther, it keeps the same view, just closer or farther away.
        val normal = scene.cameraAt(30.0)
        for (k in listOf(PerfectLapScene.MinCameraDistance, 2.0, PerfectLapScene.MaxCameraDistance)) {
            val c = scene.cameraAt(30.0, k)
            assertEquals(PerfectLapScene.CameraUpMeters * k, c.eye.z, 1e-9)
            val a = normal.eye - normal.target
            val b = c.eye - c.target
            assertEquals(a.z / a.length, b.z / b.length, 1e-9)
        }
        val farthest = PerfectLapScene.CameraUpMeters * PerfectLapScene.MaxCameraDistance
        assertEquals(farthest, scene.cameraAt(30.0, 10.0).eye.z, 1e-9)
    }

    /** A few frames as SVG under build/tmp for a look; not uploaded with the reports. */
    private fun writeFrames(scene: PerfectLapScene, ground: SceneGround) {
        val dir = File("build/tmp/perfect-lap-3d").apply { mkdirs() }
        val colors = mapOf(
            ScenePaint.Road to "#2b3136", ScenePaint.RoadEdge to "#cfd6db", ScenePaint.Braking to "#FF4B4B",
            ScenePaint.Coasting to "#9aa3ab", ScenePaint.Accelerating to "#22D37A", ScenePaint.NoPedal to "#9aa3ab",
            ScenePaint.Car to "#FCB203", ScenePaint.CarDark to "#3a2c00", ScenePaint.Ghost to "#ffffff",
            ScenePaint.CarHalo to "#FCB203", ScenePaint.GhostHalo to "#ffffff", ScenePaint.LineEdge to "#000000",
            ScenePaint.BrakeBar to "#FF4B4B", ScenePaint.GasBar to "#22D37A",
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
            val words = f.labels.joinToString("") { l ->
                val at = String.format(Locale.ROOT, "x=\"%.0f\" y=\"%.0f\" font-size=\"%.0f\"", l.x, l.y, l.size)
                "<text $at fill=\"#fff\">${l.text}</text>"
            }
            File(dir, String.format(Locale.ROOT, "frame-%05.1f.svg", t)).writeText(
                "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 1280 720\">" +
                    "<rect width=\"1280\" height=\"720\" fill=\"#2f6b3a\"/>$body$words</svg>",
            )
        }
        assertTrue(abs(scene.gapAt(0.0)) < 0.05)
    }
}
