@file:Suppress("MagicNumber") // Car and road sizes in metres, and heights above the road.

package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.optimal.PedalState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

// One picture of the perfect lap in 3D (FBBD-308), as flat shapes in screen pixels in drawing
// order, so the player and the video draw exactly the same with any 2D canvas.

/** What a shape is, so the drawing side gives it its colour. */
enum class ScenePaint {
    Road, RoadEdge, Braking, Coasting, Accelerating, NoPedal, CarHalo, Car, CarDark, GhostHalo, Ghost,
}

/** A filled polygon in screen pixels; [z] is its distance from the camera, metres. */
class SceneShape(val xs: FloatArray, val ys: FloatArray, val paint: ScenePaint, val z: Double)

/** A picture: shapes in drawing order, and where the best lap's car is on screen for its label (null out of view). */
class SceneFrame(val shapes: List<SceneShape>, val ghostLabel: Pair<Float, Float>?)

/** Width of the perfect lap's line on the road, metres. */
const val SceneLineMeters = 1.6

private const val EdgeMeters = 0.5

/** Band points farther apart than this are not joined: the road would cross the infield. */
private const val JoinMeters = 15.0

/** Under each car, a disc this wide shows where it is from far above. */
private const val HaloMeters = 4.5
private const val NearMeters = 0.5
private const val CarHalfLength = 2.2
private const val CarHalfWidth = 0.9
private const val CarHeight = 1.0
private const val CabinHeight = 1.45
private const val CabinBack = 1.1
private const val CabinFront = 0.5
private const val CabinHalfWidth = 0.75

/** East and north, metres. */
private typealias Xy = Pair<Double, Double>
private const val LabelAboveMeters = 4.0
private const val HaloSides = 24

/**
 * The scene's fixed ground: road, road edges and the perfect lap's line, as quads in metres, built
 * once and drawn every frame.
 */
class SceneGround(scene: PerfectLapScene) {
    /** Road, then its edges, then the line: the ground is flat and seen from above, so this is the drawing order. */
    internal val quads: List<Pair<DoubleArray, ScenePaint>> = run {
        val road = mutableListOf<Pair<DoubleArray, ScenePaint>>()
        val edges = mutableListOf<Pair<DoubleArray, ScenePaint>>()
        // One road round the lap: each corner's band, joined to the next where the windows meet.
        val left = scene.corners.flatMap { it.left }
        val right = scene.corners.flatMap { it.right }
        for (i in 0 until left.size - 1) {
            val l0 = left[i]
            val l1 = left[i + 1]
            val r0 = right[i]
            val r1 = right[i + 1]
            if (hypot(l1.first - l0.first, l1.second - l0.second) > JoinMeters) continue
            road += quad(l0, l1, r1, r0, 0.0) to ScenePaint.Road
            edges += ribbon(l0, l1, EdgeMeters, 0.0).map { it to ScenePaint.RoadEdge }
            edges += ribbon(r0, r1, EdgeMeters, 0.0).map { it to ScenePaint.RoadEdge }
        }
        val line = scene.perfect.zipWithNext().flatMap { (a, b) ->
            val paint = when (a.pedal) {
                PedalState.Braking -> ScenePaint.Braking
                PedalState.Coasting -> ScenePaint.Coasting
                PedalState.Accelerating -> ScenePaint.Accelerating
                null -> ScenePaint.NoPedal
            }
            ribbon(a.eastMeters to a.northMeters, b.eastMeters to b.northMeters, SceneLineMeters, 0.0)
                .map { it to paint }
        }
        road + edges + line
    }

    private fun quad(a: Xy, b: Xy, c: Xy, d: Xy, h: Double) =
        doubleArrayOf(a.first, a.second, h, b.first, b.second, h, c.first, c.second, h, d.first, d.second, h)

    private fun ribbon(a: Xy, b: Xy, w: Double, h: Double): List<DoubleArray> {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val l = hypot(dx, dy)
        if (l < 1e-6) return emptyList()
        val nx = -dy / l * w / 2
        val ny = dx / l * w / 2
        return listOf(
            quad(
                a.first + nx to a.second + ny, b.first + nx to b.second + ny,
                b.first - nx to b.second - ny, a.first - nx to a.second - ny, h,
            ),
        )
    }
}

/** The picture at [seconds], [width] by [height] pixels. */
fun sceneFrame(scene: PerfectLapScene, ground: SceneGround, seconds: Double, width: Int, height: Int): SceneFrame {
    val cam = Camera(scene.cameraAt(seconds, width.toDouble() / height), width, height)
    val shapes = ArrayList<SceneShape>(ground.quads.size / 2)
    for ((q, paint) in ground.quads) cam.polygon(q, paint)?.let { shapes += it }
    val ghost = scene.bestAt(seconds)
    // The two cars on top of the road, the farther first; each car's sides before its roof.
    shapes += listOf(
        car(cam, ghost, ScenePaint.Ghost, ScenePaint.Ghost, ScenePaint.GhostHalo),
        car(cam, scene.perfectAt(seconds), ScenePaint.Car, ScenePaint.CarDark, ScenePaint.CarHalo),
    )
        .sortedByDescending { it.firstOrNull()?.z ?: 0.0 }
        .flatten()
    return SceneFrame(shapes, cam.point(ghost.eastMeters, ghost.northMeters, LabelAboveMeters))
}

private fun car(cam: Camera, at: CarAt, body: ScenePaint, dark: ScenePaint, halo: ScenePaint): List<SceneShape> {
    val fx = cos(at.heading)
    val fy = sin(at.heading)
    fun v(a: Double, b: Double, h: Double) =
        doubleArrayOf(at.eastMeters + fx * a + fy * b, at.northMeters + fy * a - fx * b, h)
    fun face(vararg p: DoubleArray) = p.fold(DoubleArray(0)) { acc, x -> acc + x }
    val l = CarHalfLength
    val w = CarHalfWidth
    val disc = DoubleArray(HaloSides * 3) { k ->
        val a = 2 * Math.PI * (k / 3) / HaloSides
        when (k % 3) {
            0 -> at.eastMeters + cos(a) * HaloMeters
            1 -> at.northMeters + sin(a) * HaloMeters
            else -> 0.0
        }
    }
    val faces = listOf(
        disc to halo,
        face(v(-l, -w, 0.0), v(l, -w, 0.0), v(l, -w, CarHeight), v(-l, -w, CarHeight)) to dark,
        face(v(-l, w, 0.0), v(l, w, 0.0), v(l, w, CarHeight), v(-l, w, CarHeight)) to dark,
        face(v(-l, -w, 0.0), v(-l, w, 0.0), v(-l, w, CarHeight), v(-l, -w, CarHeight)) to dark,
        face(v(l, -w, 0.0), v(l, w, 0.0), v(l, w, CarHeight), v(l, -w, CarHeight)) to dark,
        face(v(-l, -w, CarHeight), v(l, -w, CarHeight), v(l, w, CarHeight), v(-l, w, CarHeight)) to body,
        face(v(-CabinBack, -CabinHalfWidth, CabinHeight), v(CabinFront, -CabinHalfWidth, CabinHeight),
            v(CabinFront, CabinHalfWidth, CabinHeight), v(-CabinBack, CabinHalfWidth, CabinHeight)) to dark,
    )
    // A car is drawn as one: its faces keep its distance.
    val z = cam.depth(at.eastMeters, at.northMeters, 0.0) - CarHalfLength
    return faces.mapNotNull { (q, p) -> cam.polygon(q, p)?.let { SceneShape(it.xs, it.ys, it.paint, z) } }
}

/** A pinhole camera at a [CameraPose], horizontal field of view [PerfectLapScene.CameraFovDegrees]. */
private class Camera(pose: CameraPose, private val width: Int, private val height: Int) {
    private val eye = pose.eye
    private val f: Vec3
    private val r: Vec3
    private val u: Vec3
    private val focal = width / 2 / tan(Math.toRadians(PerfectLapScene.CameraFovDegrees) / 2)

    init {
        val d = pose.target - eye
        f = Vec3(d.x / d.length, d.y / d.length, d.z / d.length)
        val rr = Vec3(f.y, -f.x, 0.0)
        val rl = rr.length.takeIf { it > 1e-9 } ?: 1.0
        r = Vec3(rr.x / rl, rr.y / rl, 0.0)
        u = Vec3(r.y * f.z - r.z * f.y, r.z * f.x - r.x * f.z, r.x * f.y - r.y * f.x)
    }

    fun depth(x: Double, y: Double, z: Double) = Vec3(x, y, z).minus(eye).dot(f)

    fun point(x: Double, y: Double, z: Double): Pair<Float, Float>? {
        val d = Vec3(x, y, z) - eye
        val cz = d.dot(f)
        if (cz < NearMeters) return null
        val sx = width / 2 + focal * d.dot(r) / cz
        val sy = height / 2 - focal * d.dot(u) / cz
        return if (sx in 0.0..width.toDouble() && sy in 0.0..height.toDouble()) sx.toFloat() to sy.toFloat() else null
    }

    /** [q] (x, y, z triples) projected and clipped at the near plane; null behind the camera or off the picture. */
    @Suppress("ReturnCount", "ComplexCondition") // Each way a shape can fall out of the picture.
    fun polygon(q: DoubleArray, paint: ScenePaint): SceneShape? {
        val n = q.size / 3
        val cx = DoubleArray(n)
        val cy = DoubleArray(n)
        val cz = DoubleArray(n)
        for (i in 0 until n) {
            val d = Vec3(q[i * 3] - eye.x, q[i * 3 + 1] - eye.y, q[i * 3 + 2] - eye.z)
            cx[i] = d.dot(r)
            cy[i] = d.dot(u)
            cz[i] = d.dot(f)
        }
        val xs = ArrayList<Float>(n + 2)
        val ys = ArrayList<Float>(n + 2)
        var zSum = 0.0
        fun emit(x: Double, y: Double, z: Double) {
            xs += (width / 2 + focal * x / z).toFloat()
            ys += (height / 2 - focal * y / z).toFloat()
            zSum += z
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val inI = cz[i] >= NearMeters
            val inJ = cz[j] >= NearMeters
            if (inI) emit(cx[i], cy[i], cz[i])
            if (inI != inJ) {
                val t = (NearMeters - cz[i]) / (cz[j] - cz[i])
                emit(cx[i] + t * (cx[j] - cx[i]), cy[i] + t * (cy[j] - cy[i]), NearMeters)
            }
        }
        if (xs.size < 3) return null
        if (xs.all { it < 0 } || xs.all { it > width } || ys.all { it < 0 } || ys.all { it > height }) return null
        return SceneShape(xs.toFloatArray(), ys.toFloatArray(), paint, zSum / xs.size)
    }
}
