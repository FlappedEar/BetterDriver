package com.flappedear.betterdriver.videoexport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.geo.GeoPoint
import com.flappedear.betterdriver.timing.geo.LocalFrame
import com.flappedear.betterdriver.timing.geo.Xy
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.lapDelta
import com.flappedear.betterdriver.timing.overlay.lapTime
import com.flappedear.betterdriver.timing.overlay.steeringText
import com.flappedear.betterdriver.timing.replay.replayLaps
import com.flappedear.betterdriver.timing.track.Track
import com.flappedear.betterdriver.timing.track.TrackLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The video overlay in FlappedEar Overlays' Tech HUD layout (FBBD-347), drawn as the export draws it.
 * Pictures go to build/outputs/roborazzi/layout/tech-hud/ for a look.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TechHudPainterTest {
    private val center = GeoPoint(52.0, 19.0)
    private val frame = LocalFrame(center)

    @Test
    fun textsAreOverlaysOwn() {
        assertEquals("1:23.456", lapTime(83_456_400))
        assertEquals("0:00.000", lapTime(-5))
        assertEquals("-0.123", lapDelta(-0.1234))
        assertEquals("+0.500", lapDelta(0.5))
        assertEquals("90° L", steeringText(90.4))
        assertEquals("450° R", steeringText(-450.0))
        assertEquals("0°", steeringText(0.3))
        assertEquals("—", steeringText(null))
    }

    @Test
    fun everyWidgetIsDrawnWhereTheTemplatePutsItAndNothingElse() {
        val data = drive(withCar = true)
        val painter = TechHudPainter(HudFonts.load(RuntimeEnvironment.getApplication()), data)
        val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        val t = lapStart(data) + 12_000_000
        val l = painter.layout(bmp.width, bmp.height)
        assertEquals(HudWidget.entries.toSet(), l.boxes.keys)
        painter.draw(Canvas(bmp), l, t, data.at(t))

        // Each plate's amber tab at its top left; the picture's middle is left alone.
        val plates = listOf(
            HudWidget.Lap, HudWidget.Pedals, HudWidget.Oil, HudWidget.Tyres, HudWidget.Steering,
            HudWidget.HeartRate, HudWidget.GReadout, HudWidget.TrackMap,
        )
        for (w in plates) {
            val b = l.boxes.getValue(w)
            assertEquals("$w tab", Amber, bmp.getPixel(b.left.toInt() + 6, b.top.toInt() + 1))
        }
        assertEquals(0, Color.alpha(bmp.getPixel(960, 540)))
        assertEquals(0, Color.alpha(bmp.getPixel(960, 400)))
        // The radar has its amber dot; the map its trail.
        assertTrue("radar dot", amber(bmp, l.boxes.getValue(HudWidget.GRadar)) > 100)
        assertTrue("map trail and car", amber(bmp, l.boxes.getValue(HudWidget.TrackMap)) > 300)
        save(bmp, "full")
    }

    @Test
    fun aPhoneOnlyDriveShowsOnlyWhatItHas() {
        val data = drive(withCar = false)
        val painter = TechHudPainter(HudFonts.load(RuntimeEnvironment.getApplication()), data)
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val t = lapStart(data) + 5_000_000
        val l = painter.layout(bmp.width, bmp.height)
        assertEquals(
            setOf(HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout, HudWidget.TrackMap),
            l.boxes.keys,
        )
        painter.draw(Canvas(bmp), l, t, data.at(t))
        val pedals = 0.292f * bmp.width + 6
        assertEquals("no pedal plate", 0, Color.alpha(bmp.getPixel(pedals.toInt(), (0.829f * bmp.height).toInt() + 1)))
        save(bmp, "phone-only")
    }

    /** Where the session's best lap starts. */
    private fun lapStart(data: OverlayData): Long {
        checkNotNull(data.bestLapMicros) { "no best lap" }
        val best = replayLaps("s", sets(withCar = false), track()).best
        return checkNotNull(best) { "no best lap" }.lap.startMicros
    }

    /** Three laps round a 150 m circle at 30 m/s, 25 fixes a second, anticlockwise from its south point. */
    private fun sets(withCar: Boolean): List<SampleSet> {
        val lapSeconds = 2 * PI * Radius / Speed
        return (0 until (lapSeconds * 3 * Hz).toInt()).map { i ->
            val t = i.toDouble() / Hz
            val a = -PI / 2 + t / lapSeconds * 2 * PI
            val g = frame.toGeo(Xy(cos(a) * Radius, sin(a) * Radius))
            val car = if (!withCar) {
                emptyList()
            } else {
                val wave = sin(t)
                listOf(
                    ChannelValue(300, 5200 + 1500 * wave),
                    ChannelValue(301, 60 + 40 * wave),
                    ChannelValue(302, 0.0),
                    ChannelValue(303, 35 * wave),
                    ChannelValue(304, 153.0),
                    ChannelValue(305, 119.0),
                    ChannelValue(306, 101.0),
                    ChannelValue(307, 99.0),
                    ChannelValue(308, 85.0),
                    ChannelValue(309, 2.1),
                )
            }
            SampleSet(
                (t * 1e6).toLong() + 1_000_000,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, g.lat),
                    ChannelValue(GnssChannels.LON.id, g.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, Speed * 3.6),
                ) + car,
            )
        }
    }

    private fun track() = Track(
        id = "circle",
        name = "Circle",
        center = center,
        startFinish = TrackLine.across(frame.toGeo(Xy(0.0, -Radius)), 90.0, 30.0),
    )

    private fun drive(withCar: Boolean): OverlayData {
        val sets = sets(withCar)
        val channels = listOf(
            ChannelDef(300, "obd_rpm", "rpm", "obd", 0),
            ChannelDef(301, "obd_accelerator", "%", "obd", 0),
            ChannelDef(302, "obd_brake", "%", "obd", 0),
            ChannelDef(303, "obd_steering_angle", "deg", "obd", 1),
            ChannelDef(304, "heart_rate", "bpm", "heart_rate", 0),
            ChannelDef(305, "obd_oil_temp", "°C", "obd", 0),
            ChannelDef(306, "obd_atf_temp", "°C", "obd", 0),
            ChannelDef(307, "obd_coolant_temp", "°C", "obd", 0),
            ChannelDef(308, "tyre_temp_fl", "°C", "tpms", 0),
            ChannelDef(309, "tyre_pressure_fl", "bar", "tpms", 2),
        )
        return OverlayData(sets, replayLaps("s", sets, track()), channels = if (withCar) channels else emptyList())
    }

    private fun amber(bmp: Bitmap, b: com.flappedear.betterdriver.timing.overlay.Box): Int {
        var n = 0
        for (x in b.left.toInt() until b.right.toInt()) {
            for (y in b.top.toInt() until b.bottom.toInt()) if (bmp.getPixel(x, y) == Amber) n++
        }
        return n
    }

    /** Over a grey picture, as the Overlays guide shows its templates. */
    private fun save(overlay: Bitmap, name: String) {
        val out = Bitmap.createBitmap(overlay.width, overlay.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Backdrop)
        c.drawBitmap(overlay, 0f, 0f, null)
        val dir = File("build/outputs/roborazzi/layout/tech-hud").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        const val Radius = 150.0
        const val Speed = 30.0
        const val Hz = 25
        const val Amber = 0xFFFCB203.toInt()
        const val Backdrop = 0xFF3A424C.toInt()
    }
}
