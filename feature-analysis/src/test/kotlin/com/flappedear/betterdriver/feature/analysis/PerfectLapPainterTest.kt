package com.flappedear.betterdriver.feature.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import com.flappedear.betterdriver.analysis.line.perfectLapScene
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The perfect lap in 3D (FBBD-308, FBBD-316) drawn as the player and the video draw it: road or
 * the map photo, line and both cars.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PerfectLapPainterTest {
    private val palette = ScenePalette(
        ground = 0xFF102010.toInt(), road = 0xFF303030.toInt(), edge = 0xFFC0C0C0.toInt(),
        braking = 0xFFFF0000.toInt(), coasting = 0xFF808080.toInt(), accelerating = 0xFF00FF00.toInt(),
        car = 0xFFFCB203.toInt(), text = 0xFFFFFFFF.toInt(), textDim = 0xFFA0A0A0.toInt(), panel = 0xB0000000.toInt(),
    )

    @Test
    fun everyCornerShowsTheRoadTheLineAndTheCar() {
        val scene = perfectLapScene(previewPerfectLapPlan())!!
        val painter = PerfectLapPainter(scene, palette)
        val bmp = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        for (corner in scene.corners) {
            painter.draw(Canvas(bmp), (corner.startSeconds + corner.endSeconds) / 2, bmp.width, bmp.height)
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val count = px.asList().groupingBy { it }.eachCount()
            assertTrue("road at ${corner.name}", (count[palette.road] ?: 0) > 2000)
            val line = listOf(palette.braking, palette.coasting, palette.accelerating).sumOf { count[it] ?: 0 }
            assertTrue("line at ${corner.name}", line > 200)
            // The car, seen from close behind (UI-200 A).
            val amber = px.count { amberish(it) }
            assertTrue("car at ${corner.name}: $amber", amber > 300)
        }
    }

    @Test
    fun theMapPhotoCoversTheGroundAndTheLineStaysOnTop() {
        val scene = perfectLapScene(previewPerfectLapPlan())!!
        val blue = 0xFF1040C0.toInt()
        val tile = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(blue) }
        var asked = 0
        val painter = PerfectLapPainter(scene, palette, { _, _, _ -> asked++; tile }, "© MapTiler")
        val bmp = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val corner = scene.corners.first()
        painter.draw(Canvas(bmp), (corner.startSeconds + corner.endSeconds) / 2, bmp.width, bmp.height)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        assertTrue("tiles asked: $asked", asked > 0)
        // The photo is under most of the picture, the drawn road is covered, the line is on top.
        assertTrue("photo ${px.count { it == blue }}", px.count { it == blue } > px.size / 3)
        assertTrue("road left ${px.count { it == palette.road }}", px.count { it == palette.road } < 2000)
        val line = listOf(palette.braking, palette.coasting, palette.accelerating).sumOf { c -> px.count { it == c } }
        assertTrue("line $line", line > 200)
    }

    private fun amberish(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return r - b > 40 && g - b > 20 && r >= g
    }
}
