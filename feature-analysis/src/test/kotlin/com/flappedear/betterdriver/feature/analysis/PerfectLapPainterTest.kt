package com.flappedear.betterdriver.feature.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import com.flappedear.betterdriver.analysis.line.perfectLapScene
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** The perfect lap in 3D (FBBD-308) drawn as the player and the video draw it: road, line and both cars. */
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
            val count = px.groupingBy { it }.eachCount()
            assertTrue("road at ${corner.name}", (count[palette.road] ?: 0) > 2000)
            val line = listOf(palette.braking, palette.coasting, palette.accelerating).sumOf { count[it] ?: 0 }
            assertTrue("line at ${corner.name}", line > 200)
            assertTrue("car at ${corner.name}", (count[palette.car] ?: 0) > 5)
        }
    }
}
