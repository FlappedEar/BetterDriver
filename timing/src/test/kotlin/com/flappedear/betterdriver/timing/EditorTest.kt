@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.fake.FakeGnss
import com.flappedear.betterdriver.timing.edit.LapPath
import com.flappedear.betterdriver.timing.edit.LineRef
import com.flappedear.betterdriver.timing.edit.line
import com.flappedear.betterdriver.timing.edit.lineRefs
import com.flappedear.betterdriver.timing.edit.sectorsInLapOrder
import com.flappedear.betterdriver.timing.edit.withLine
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.geo.distanceMeters
import com.flappedear.betterdriver.timing.live.Fix
import com.flappedear.betterdriver.timing.live.timeSession
import com.flappedear.betterdriver.timing.map.TileSources
import com.flappedear.betterdriver.timing.map.WebMercator
import com.flappedear.betterdriver.timing.map.WorldPx
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The track editor's geometry (FBBD-56): map projection, placing lines from a lap, line order. */
class EditorTest {
    private val gnss = FakeGnss()
    private val center = GeoPoint(gnss.centerLat, gnss.centerLon)

    /** Two and a half laps of the fake ring, counter-clockwise, at 25 Hz. */
    private val fixes = (0L..(25 * 100)).map { n ->
        val v = gnss.fix(n).associate { it.channelId to it.value }
        Fix(n * 40_000, v.getValue(2), v.getValue(3))
    }
    private val path = LapPath(fixes.map { GeoPoint(it.lat, it.lon) })

    /** A point at [angleDeg] around the ring (0 = east, counter-clockwise), [radius] from its centre. */
    private fun ring(angleDeg: Double, radius: Double = gnss.radiusM): GeoPoint {
        val a = Math.toRadians(angleDeg)
        return path.frame.toGeo(path.frame.toXy(center) + Xy(radius * cos(a), radius * sin(a)))
    }

    private val track = Track(id = "ring", name = "Ring", center = center)

    @Test
    fun `web mercator matches the tile scheme`() {
        val origin = WebMercator.toPx(GeoPoint(0.0, 0.0), 0.0)
        assertEquals(128.0, origin.x, 1e-9)
        assertEquals(128.0, origin.y, 1e-9)
        val p = GeoPoint(52.4, 16.83)
        val back = WebMercator.toGeo(WebMercator.toPx(p, 17.5), 17.5)
        assertEquals(0.0, distanceMeters(p, back), 1e-3)
        assertEquals(156_543.03, WebMercator.metersPerPixel(0.0, 0.0), 0.01)
        val b = WebMercator.tileBounds(1, 1, 0)
        assertEquals(listOf(0.0, 0.0), listOf(b[0], b[1]).map { Math.round(it).toDouble() })
        assertEquals(20_037_508.34, b[2], 0.01)
        // Tile x of 16.83° E at zoom 10 is floor((16.83 + 180) / 360 * 1024) = 559.
        assertEquals(559, (WebMercator.toPx(p, 10.0).x / 256).toInt())
        assertEquals(GeoPoint(0.0, -180.0), WebMercator.toGeo(WorldPx(0.0, 128.0), 0.0))
    }

    @Test
    fun `satellite in Poland, street map elsewhere`() {
        assertSame(TileSources.Orthophoto, TileSources.forPlace(GeoPoint(52.4206, 16.8114))) // Tor Poznań
        assertSame(TileSources.Orthophoto, TileSources.forPlace(GeoPoint(50.8136, 20.5378))) // Tor Kielce
        assertSame(TileSources.OpenStreetMap, TileSources.forPlace(GeoPoint(50.7914, 12.6888))) // Sachsenring
        val url = TileSources.Orthophoto.url(1, 1, 0)
        assertEquals(true, url.contains("CRS=EPSG:3857&BBOX=0.000,0.000,20037508.343,20037508.343"), url)
    }

    @Test
    fun `a tap near the lap places a line square to it that times laps`() {
        // 8 m outside the ring at its east point, where the car heads north.
        val hit = assertNotNull(path.nearest(ring(0.0, gnss.radiusM + 8)))
        // FakeGnss uses 111 320 m per degree, the frame the mean Earth radius: 0.1 % apart.
        assertEquals(8.0, hit.offMeters, 0.5)
        // The path starts here, so the heading is taken over the path ahead only.
        assertEquals(0.0, (hit.headingDeg + 180) % 360 - 180, 1.5)
        val line = assertNotNull(path.lineAt(ring(0.0, gnss.radiusM + 8)))
        assertEquals(30.0, distanceMeters(line.left, line.right), 0.01)
        // Left of a car heading north is west, towards the ring's centre.
        assertEquals(true, line.left.lon < line.right.lon)
        val laps = timeSession(track.copy(startFinish = line), fixes.asSequence()).laps
        assertEquals(3, laps.size)
        laps.drop(1).forEach { assertEquals(40_000L, it.timeMillis) }
    }

    @Test
    fun `a tap far from the lap places nothing`() {
        assertNull(path.nearest(ring(45.0, gnss.radiusM + 40)))
        assertNull(path.lineAt(center))
    }

    @Test
    fun `a line drawn the wrong way round is turned to the direction of travel`() {
        val line = assertNotNull(path.lineAt(ring(90.0)))
        assertEquals(line, path.oriented(line.flipped()))
        assertEquals(line, path.oriented(line))
        // Off the path: left as drawn.
        val away = TrackLine(ring(0.0, 400.0), ring(0.0, 430.0))
        assertEquals(away, path.oriented(away))
    }

    @Test
    fun `sector lines are kept in lap order`() {
        val sf = assertNotNull(path.lineAt(ring(0.0)))
        val at90 = assertNotNull(path.lineAt(ring(90.0)))
        val at180 = assertNotNull(path.lineAt(ring(180.0)))
        val at270 = assertNotNull(path.lineAt(ring(270.0)))
        val t = track.copy(startFinish = sf, sectorLines = listOf(at270, at90, at180)).sectorsInLapOrder(path)
        assertEquals(listOf(at90, at180, at270), t.sectorLines)
        // Start/finish at 180°: the lap starts there, so 270° comes first.
        val fromWest = t.copy(startFinish = at180, sectorLines = listOf(at90, at270, sf)).sectorsInLapOrder(path)
        assertEquals(listOf(at270, sf, at90), fromWest.sectorLines)
    }

    @Test
    fun `lines are set, replaced, added and removed by reference`() {
        val a = TrackLine(ring(10.0, 190.0), ring(10.0, 210.0))
        val b = TrackLine(ring(20.0, 190.0), ring(20.0, 210.0))
        var t = track.withLine(LineRef.StartFinish, a).withLine(LineRef.Sector(0), a).withLine(LineRef.Sector(1), b)
        assertEquals(listOf(a, b), t.sectorLines)
        assertEquals(a, t.line(LineRef.StartFinish))
        t = t.withLine(LineRef.Sector(0), null).withLine(LineRef.PitEntry, b)
        assertEquals(listOf(b), t.sectorLines)
        assertEquals(b, t.line(LineRef.PitEntry))
        assertNull(t.line(LineRef.PitExit))
        assertEquals(
            listOf(LineRef.StartFinish, LineRef.Sector(0), LineRef.PitEntry, LineRef.PitExit),
            t.lineRefs(),
        )
    }
}
