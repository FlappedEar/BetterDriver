@file:Suppress("MagicNumber") // Car and road sizes in metres, and heights above the road.

package com.flappedear.betterdriver.analysis.line

import com.flappedear.betterdriver.analysis.geo.GeoCoordinate
import com.flappedear.betterdriver.analysis.geo.projectCoordinate
import com.flappedear.betterdriver.analysis.geo.unprojectCoordinate
import com.flappedear.betterdriver.analysis.optimal.PedalState
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

// One picture of the perfect lap in 3D (FBBD-308, FBBD-316), as flat shapes in screen pixels in
// drawing order, so the player and the video draw exactly the same with any 2D canvas.

/** What a shape is, so the drawing side gives it its colour. */
enum class ScenePaint {
    Road, RoadEdge, LineEdge, Braking, Coasting, Accelerating, NoPedal, BrakeBar, GasBar,
    CarHalo, Car, CarDark, GhostHalo, Ghost,
}

/** The shapes that are the ground under the map photo: drawn first, and covered where a photo tile is shown. */
val ScenePaint.underMap: Boolean get() = this == ScenePaint.Road || this == ScenePaint.RoadEdge

/** A filled polygon in screen pixels; [z] is its distance from the camera, metres. */
class SceneShape(val xs: FloatArray, val ys: FloatArray, val paint: ScenePaint, val z: Double)

/** A word on the road in screen pixels, [size] its text height: BRAKE or GAS beside its bar. */
class SceneLabel(val text: String, val x: Float, val y: Float, val size: Float)

/**
 * A piece of a map tile (Web Mercator) on screen: the part from ([u0], [v0]) to ([u1], [v1])
 * (fractions of the tile, 0 at its top left) lands on the four screen corners [xs], [ys], in the
 * order top left, top right, bottom right, bottom left.
 */
class SceneTile(id: IntArray, uv: FloatArray, val xs: FloatArray, val ys: FloatArray) {
    val z = id[0]
    val x = id[1]
    val y = id[2]
    val u0 = uv[0]
    val v0 = uv[1]
    val u1 = uv[2]
    val v1 = uv[3]
}

/**
 * A picture: shapes in drawing order (the [ScenePaint.underMap] ones first, then the map's [tiles]
 * go over them, then the rest), where the best lap's car is on screen for its label (null out of
 * view), and the BRAKE and GAS words.
 */
class SceneFrame(
    val shapes: List<SceneShape>,
    val ghostLabel: Pair<Float, Float>?,
    val labels: List<SceneLabel> = emptyList(),
    val tiles: List<SceneTile> = emptyList(),
)

/** Width of the perfect lap's line on the road, metres: about a car's width (UI-202 C). */
const val SceneLineMeters = 2.4

/** The dark edge round the line, so it shows on a photo, metres each side. */
private const val LineEdgeMeters = 0.3

/** The map's zoom under the road: about 0.2 m a pixel in a 512 px tile at 50° north. */
const val SceneMapZoom = 18

/** Map around the road this far out, metres. */
private const val MapMarginMeters = 150.0

/**
 * The coarser map under it, out to the horizon of the farthest camera: about 0.8 m a pixel, so the
 * picture is photo to its top edge, and a place whose fine tile is not loaded yet is never empty.
 */
const val SceneFarZoom = 16
private const val FarMarginMeters = 1500.0

/** Fine tiles are left to the coarse map beyond this distance from the camera, metres. */
private const val NearTileMeters = 400.0

/** A BRAKE or GAS bar across the road is this long along it, metres. */
private const val BarMeters = 1.2

/** A pedal has to stay this long for its bar, so a flicker in the data does not paint one, metres. */
private const val BarRunMeters = 12.0

/** Two bars of the same pedal closer than this along the lap are one, metres. */
private const val BarGapMeters = 40.0

/** The word stands this far outside the road's edge, metres; its height on the road, metres. */
private const val LabelOutMeters = 3.0
private const val LabelMeters = 1.8
private const val MaxSubdivision = 5

private const val EdgeMeters = 0.5

/** Band points farther apart than this are not joined: the road would cross the infield. */
private const val JoinMeters = 15.0

/** Under each car, a disc this wide shows where it is from far above. */
private const val HaloMeters = 3.0
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
    private val edgesLeft = scene.corners.flatMap { it.left }
    private val edgesRight = scene.corners.flatMap { it.right }

    /** Where braking starts and where the gas goes back on (UI-202 C): bar across the road and its word's place. */
    internal val bars: List<Bar> = bars(scene)

    /**
     * Road, then its edges, then the line's dark edge, the line and the bars: the ground is flat and
     * seen from above, so this is the drawing order.
     */
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
        val lineEdge = scene.perfect.zipWithNext().flatMap { (a, b) ->
            val w = SceneLineMeters + 2 * LineEdgeMeters
            ribbon(a.eastMeters to a.northMeters, b.eastMeters to b.northMeters, w, 0.0)
                .map { it to ScenePaint.LineEdge }
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
        road + edges + lineEdge + line + bars.map { it.quad to it.paint }
    }

    /**
     * The map tiles round the road, each with its corners in scene metres: top left, top right,
     * bottom right, bottom left; the coarse ones ([SceneFarZoom]) first, as they are drawn.
     */
    internal val tiles: List<Pair<IntArray, DoubleArray>> =
        mapTiles(scene, SceneFarZoom, FarMarginMeters) + mapTiles(scene, SceneMapZoom, MapMarginMeters)

    internal class Bar(val quad: DoubleArray, val paint: ScenePaint, val text: String, val label: DoubleArray)

    private fun bars(scene: PerfectLapScene): List<Bar> {
        if (edgesLeft.isEmpty() || edgesRight.isEmpty()) return emptyList()
        val lastAt = HashMap<PedalState, Double>()
        return barStarts(scene.perfect).filter { i ->
            // Not one just after another of the same pedal.
            val p = scene.perfect[i]
            val pedal = p.pedal ?: return@filter false
            val keep = lastAt[pedal]?.let { p.lapMeters - it >= BarGapMeters } ?: true
            if (keep) lastAt[pedal] = p.lapMeters
            keep
        }.mapNotNull { bar(scene.perfect, it) }
    }

    /** Where braking or the gas starts along [line] and lasts at least [BarRunMeters]. */
    private fun barStarts(line: List<ScenePoint>): List<Int> = (1 until line.size).filter { i ->
        val pedal = line[i].pedal
        val starts = (pedal == PedalState.Braking || pedal == PedalState.Accelerating) && line[i - 1].pedal != pedal
        val ends = if (starts) line.drop(i).firstOrNull { it.pedal != pedal } ?: line.last() else line[i]
        starts && ends.lapMeters - line[i].lapMeters >= BarRunMeters
    }

    /** The bar across the road at point [i] of [line], edge to edge, and its word's place outside the left edge. */
    private fun bar(line: List<ScenePoint>, i: Int): Bar? {
        val here = line[i]
        val p = here.eastMeters to here.northMeters
        // Both ends from one cross-section of the road, so a bar never spans to a section running beside it.
        val k = edgesLeft.indices.minBy {
            val (l, r) = edgesLeft[it] to edgesRight[it]
            hypot((l.first + r.first) / 2 - p.first, (l.second + r.second) / 2 - p.second)
        }
        val l = edgesLeft[k]
        val r = edgesRight[k]
        val dx = here.eastMeters - line[i - 1].eastMeters
        val dy = here.northMeters - line[i - 1].northMeters
        val dl = hypot(dx, dy).takeIf { it > 1e-6 } ?: return null
        val hx = dx / dl * BarMeters / 2
        val hy = dy / dl * BarMeters / 2
        val quad = quad(
            l.first - hx to l.second - hy, l.first + hx to l.second + hy,
            r.first + hx to r.second + hy, r.first - hx to r.second - hy, 0.0,
        )
        val ox = l.first - p.first
        val oy = l.second - p.second
        val ol = hypot(ox, oy).takeIf { it > 1e-6 } ?: 1.0
        val label = doubleArrayOf(l.first + ox / ol * LabelOutMeters, l.second + oy / ol * LabelOutMeters, 0.0)
        return if (here.pedal == PedalState.Braking) Bar(quad, ScenePaint.BrakeBar, "BRAKE", label)
        else Bar(quad, ScenePaint.GasBar, "GAS", label)
    }

    private fun mapTiles(scene: PerfectLapScene, z: Int, margin: Double): List<Pair<IntArray, DoubleArray>> {
        val pts = edgesLeft + edgesRight
        if (pts.isEmpty()) return emptyList()
        val origin = scene.origin
        fun geo(e: Double, n: Double) =
            unprojectCoordinate(e, n, origin).let { it.latitudeDegrees to it.longitudeDegrees }
        val (south, west) = geo(pts.minOf { it.first } - margin, pts.minOf { it.second } - margin)
        val (north, east) = geo(pts.maxOf { it.first } + margin, pts.maxOf { it.second } + margin)
        val x0 = tileX(west, z)
        val x1 = tileX(east, z)
        val y0 = tileY(north, z)
        val y1 = tileY(south, z)
        fun corner(x: Int, y: Int): Pair<Double, Double> {
            val m = projectCoordinate(GeoCoordinate(tileLat(y, z), tileLon(x, z)), origin)
            return m.eastMeters to m.northMeters
        }
        val out = mutableListOf<Pair<IntArray, DoubleArray>>()
        for (x in x0..x1) for (y in y0..y1) {
            val c = listOf(corner(x, y), corner(x + 1, y), corner(x + 1, y + 1), corner(x, y + 1))
            out += intArrayOf(z, x, y) to DoubleArray(12) { k ->
                when (k % 3) {
                    0 -> c[k / 3].first
                    1 -> c[k / 3].second
                    else -> 0.0
                }
            }
        }
        return out
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

/**
 * The picture at [seconds], [width] by [height] pixels, the camera [distance] times as far from the
 * car as normal (pinched in the player).
 */
@Suppress("LongParameterList") // The scene, the moment, the picture's size and the camera's distance.
fun sceneFrame(
    scene: PerfectLapScene,
    ground: SceneGround,
    seconds: Double,
    width: Int,
    height: Int,
    distance: Double = 1.0,
): SceneFrame {
    val cam = Camera(scene.cameraAt(seconds, distance), width, height)
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
    val labels = ground.bars.mapNotNull { b ->
        val d = cam.depth(b.label[0], b.label[1], b.label[2])
        cam.point(b.label[0], b.label[1], b.label[2])?.let { (x, y) ->
            val size = (cam.focalPx * LabelMeters / d).toFloat().coerceIn(MinLabelPx, height * MaxLabelShare)
            SceneLabel(b.text, x, y, size)
        }
    }
    val tiles = ground.tiles.flatMap { (id, q) ->
        cam.tile(id, q, if (id[0] == SceneMapZoom) NearTileMeters else Double.MAX_VALUE)
    }
    return SceneFrame(
        shapes,
        cam.point(ghost.eastMeters, ghost.northMeters, LabelAboveMeters),
        labels,
        tiles,
    )
}

private const val MinLabelPx = 12f
private const val MaxLabelShare = 0.07f

private fun tileX(lon: Double, z: Int) = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt()

private fun tileY(lat: Double, z: Int): Int {
    val r = Math.toRadians(lat)
    return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)).toInt()
}

private fun tileLon(x: Int, z: Int) = x.toDouble() / (1 shl z) * 360.0 - 180.0

private fun tileLat(y: Int, z: Int) = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

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
    val focalPx: Double get() = focal

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

    /**
     * Map tile [id] (z, x, y) with corners [q] (scene metres) on screen. A tile reaching behind the
     * camera is cut in four, up to [MaxSubdivision] times; pieces still behind it, off the picture,
     * or wholly farther than [maxDepth] metres are left out.
     */
    fun tile(id: IntArray, q: DoubleArray, maxDepth: Double = Double.MAX_VALUE): List<SceneTile> {
        val out = mutableListOf<SceneTile>()
        // A coarser tile is cut further, into pieces as small as a fine tile's.
        val levels = MaxSubdivision + (SceneMapZoom - id[0]).coerceAtLeast(0)
        fun at(u: Double, v: Double): DoubleArray {
            // Bilinear between the four corners: top left, top right, bottom right, bottom left.
            fun c(k: Int, i: Int) = q[k * 3 + i]
            return DoubleArray(3) { i ->
                val top = c(0, i) + (c(1, i) - c(0, i)) * u
                val bottom = c(3, i) + (c(2, i) - c(3, i)) * u
                top + (bottom - top) * v
            }
        }
        fun piece(u0: Double, v0: Double, u1: Double, v1: Double, level: Int) {
            val corners = listOf(at(u0, v0), at(u1, v0), at(u1, v1), at(u0, v1))
            val zs = corners.map { depth(it[0], it[1], it[2]) }
            val behind = zs.count { it < NearMeters }
            if (zs.min() > maxDepth) return
            if (behind == 0) {
                val xs = FloatArray(corners.size)
                val ys = FloatArray(corners.size)
                corners.forEachIndexed { k, p ->
                    val d = Vec3(p[0] - eye.x, p[1] - eye.y, p[2] - eye.z)
                    xs[k] = (width / 2 + focal * d.dot(r) / zs[k]).toFloat()
                    ys[k] = (height / 2 - focal * d.dot(u) / zs[k]).toFloat()
                }
                val uv = floatArrayOf(u0.toFloat(), v0.toFloat(), u1.toFloat(), v1.toFloat())
                if (!offPicture(xs, ys)) out += SceneTile(id, uv, xs, ys)
            } else if (behind < corners.size && level < levels) {
                val um = (u0 + u1) / 2
                val vm = (v0 + v1) / 2
                piece(u0, v0, um, vm, level + 1)
                piece(um, v0, u1, vm, level + 1)
                piece(u0, vm, um, v1, level + 1)
                piece(um, vm, u1, v1, level + 1)
            }
        }
        piece(0.0, 0.0, 1.0, 1.0, 0)
        return out
    }

    /** Whether the polygon [xs], [ys] lies wholly to one side of the picture. */
    private fun offPicture(xs: FloatArray, ys: FloatArray): Boolean =
        xs.all { it < 0 } || xs.all { it > width } || ys.all { it < 0 } || ys.all { it > height }

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
        if (offPicture(xs.toFloatArray(), ys.toFloatArray())) return null
        return SceneShape(xs.toFloatArray(), ys.toFloatArray(), paint, zSum / xs.size)
    }
}
