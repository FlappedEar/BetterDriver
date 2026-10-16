package com.flappedear.betterdriver.feature.track.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.map.TileSize
import com.flappedear.betterdriver.timing.map.TileSource
import com.flappedear.betterdriver.timing.map.WebMercator
import com.flappedear.betterdriver.timing.map.WorldPx
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MinZoom = 3.0
private const val MaxZoom = 21.0
private val Ln2 = ln(2.0)
private val MapGround = Color(0xFF1C242A)

/** What part of the world the map shows: its centre and a fractional Web Mercator zoom. */
@Stable
class MapCamera(center: GeoPoint, zoom: Double) {
    var center by mutableStateOf(center)
    var zoom by mutableDoubleStateOf(zoom)

    fun toScreen(p: GeoPoint, size: Size): Offset {
        val c = WebMercator.toPx(center, zoom)
        val q = WebMercator.toPx(p, zoom)
        return Offset((q.x - c.x + size.width / 2).toFloat(), (q.y - c.y + size.height / 2).toFloat())
    }

    fun toGeo(o: Offset, size: Size): GeoPoint {
        val c = WebMercator.toPx(center, zoom)
        return WebMercator.toGeo(WorldPx(c.x + o.x - size.width / 2, c.y + o.y - size.height / 2), zoom)
    }

    fun panBy(d: Offset) {
        val c = WebMercator.toPx(center, zoom)
        center = WebMercator.toGeo(WorldPx(c.x - d.x, c.y - d.y), zoom)
    }

    /** Zooms by [factor] keeping the point under [focus] where it is. */
    fun zoomBy(factor: Float, focus: Offset, size: Size) {
        val held = toGeo(focus, size)
        zoom = (zoom + ln(factor.toDouble()) / Ln2).coerceIn(MinZoom, MaxZoom)
        panBy(focus - toScreen(held, size))
    }

    /** Shows all of [points] with [marginPx] around them, no closer than [maxZoom]. */
    fun fit(points: List<GeoPoint>, size: Size, marginPx: Float, maxZoom: Double = MaxFitZoom) {
        if (points.isEmpty() || size.width <= 2 * marginPx || size.height <= 2 * marginPx) return
        val px = points.map { WebMercator.toPx(it, 0.0) }
        val minX = px.minOf { it.x }
        val maxX = px.maxOf { it.x }
        val minY = px.minOf { it.y }
        val maxY = px.maxOf { it.y }
        val scale = min(
            (size.width - 2 * marginPx) / (maxX - minX).coerceAtLeast(MinSpan),
            (size.height - 2 * marginPx) / (maxY - minY).coerceAtLeast(MinSpan),
        )
        zoom = (ln(scale) / Ln2).coerceIn(MinZoom, maxZoom)
        center = WebMercator.toGeo(WorldPx((minX + maxX) / 2, (minY + maxY) / 2), 0.0)
    }

    private companion object {
        const val MaxFitZoom = 18.0
        const val MinSpan = 1e-9
    }
}

/**
 * A slippy map from [source] with an [overlay] drawn over it (FBBD-56). Drag pans, pinch zooms, a
 * tap reports where ([onTap]). Points in [handles] can be dragged ([onHandleDrag], then
 * [onHandleDragEnd]); a touch within 28 dp of one grabs it instead of panning.
 */
@Composable
fun TileMap(
    camera: MapCamera,
    source: TileSource,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    handles: List<GeoPoint> = emptyList(),
    onTap: (GeoPoint) -> Unit = {},
    onHandleDrag: (index: Int, to: GeoPoint) -> Unit = { _, _ -> },
    onHandleDragEnd: () -> Unit = {},
    overlay: DrawScope.(project: (GeoPoint) -> Offset) -> Unit = {},
) {
    val currentHandles by rememberUpdatedState(handles)
    val currentTap by rememberUpdatedState(onTap)
    val currentDrag by rememberUpdatedState(onHandleDrag)
    val currentDragEnd by rememberUpdatedState(onHandleDragEnd)
    Box(modifier.clipToBounds().background(MapGround)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(camera) {
                mapGestures(camera, { currentHandles }, { currentTap(it) }, { i, to -> currentDrag(i, to) }) {
                    currentDragEnd()
                }
            },
        ) {
            drawTiles(camera, source, tiles)
            overlay { camera.toScreen(it, size) }
        }
        Text(
            source.attribution,
            fontSize = 10.sp,
            color = Color(0xFFEEF2F4),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .background(Color(0x99000000))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private val HandleTouch = 28.dp

/**
 * Pan, pinch zoom and tap on the map; a touch near one of [handles] drags that handle instead.
 * Lambdas are read at each gesture, so they can change while the map is shown.
 */
private suspend fun PointerInputScope.mapGestures(
    camera: MapCamera,
    handles: () -> List<GeoPoint>,
    onTap: (GeoPoint) -> Unit,
    onDrag: (Int, GeoPoint) -> Unit,
    onDragEnd: () -> Unit,
) {
    val grabRadius = HandleTouch.toPx()
    awaitEachGesture {
        val size = Size(this.size.width.toFloat(), this.size.height.toFloat())
        val down = awaitFirstDown(requireUnconsumed = false)
        val hs = handles()
        val grabbed = hs.indices
            .map { it to (camera.toScreen(hs[it], size) - down.position).getDistance() }
            .filter { it.second <= grabRadius }
            .minByOrNull { it.second }
            ?.first
        if (grabbed != null) {
            // Keeps the handle under the same spot of the finger, so it does not jump.
            val grip = camera.toScreen(hs[grabbed], size) - down.position
            down.consume()
            drag(down.id) { change ->
                onDrag(grabbed, camera.toGeo(change.position + grip, size))
                change.consume()
            }
            onDragEnd()
            return@awaitEachGesture
        }
        var moved = false
        var travel = Offset.Zero
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            travel += pan
            if (!moved && (travel.getDistance() > viewConfiguration.touchSlop || zoom != 1f)) moved = true
            if (moved) {
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f && centroid.isSpecified) camera.zoomBy(zoom, centroid, size)
                camera.panBy(pan)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (!moved) onTap(camera.toGeo(down.position, size))
    }
}

/** Coarser tiles tried, one level at a time, while a tile is still loading. */
private const val FallbackLevels = 4

private fun DrawScope.drawTiles(camera: MapCamera, source: TileSource, tiles: TileLoader) {
    // Reading the counter makes the map redraw whenever a tile arrives.
    if (tiles.loaded < 0) return
    // A 512 px tile is used a level higher than a 256 px one: as sharp, in a quarter of the tiles.
    val shift = ln(source.imagePx.toDouble() / TileSize) / Ln2
    val z = floor(camera.zoom - shift).toInt().coerceIn(0, source.maxZoom)
    val scale = 2.0.pow(camera.zoom - z)
    val c = WebMercator.toPx(camera.center, z.toDouble())
    val n = 1 shl z
    val step = TileSize * scale
    val left = c.x - size.width / 2 / scale
    val top = c.y - size.height / 2 / scale
    val firstX = floor(left / TileSize).toInt()
    val lastX = floor((c.x + size.width / 2 / scale) / TileSize).toInt()
    val firstY = floor(top / TileSize).toInt().coerceAtLeast(0)
    val lastY = floor((c.y + size.height / 2 / scale) / TileSize).toInt().coerceAtMost(n - 1)
    val tileSide = ceil(step).toInt() + 1
    for (ty in firstY..lastY) {
        for (tx in firstX..lastX) {
            val wx = ((tx % n) + n) % n
            val dst = IntOffset(
                ((tx * TileSize - left) * scale).roundToInt(),
                ((ty * TileSize - top) * scale).roundToInt(),
            )
            val image = tiles.get(source, z, wx, ty)
            if (image != null) {
                drawImage(
                    image,
                    dstOffset = dst,
                    dstSize = IntSize(tileSide, tileSide),
                    filterQuality = FilterQuality.Low,
                )
            } else {
                drawFallback(source, tiles, z, wx, ty, dst, tileSide)
            }
        }
    }
}

/** The part of the nearest coarser tile already in memory that covers tile ([x], [y]), until it arrives. */
@Suppress("LongParameterList") // The tile, where it goes, and how big.
private fun DrawScope.drawFallback(
    source: TileSource,
    tiles: TileLoader,
    z: Int,
    x: Int,
    y: Int,
    dst: IntOffset,
    side: Int,
) {
    for (up in 1..min(FallbackLevels, z)) {
        val parent = tiles.peek(source, z - up, x shr up, y shr up) ?: continue
        val part = parent.width shr up
        if (part < 1) return
        val mask = (1 shl up) - 1
        drawImage(
            parent,
            srcOffset = IntOffset((x and mask) * part, (y and mask) * part),
            srcSize = IntSize(part, part),
            dstOffset = dst,
            dstSize = IntSize(side, side),
            filterQuality = FilterQuality.Low,
        )
        return
    }
}
