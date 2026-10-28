@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.overlay.CarValue
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.StackPosition
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-347: the video overlay in FlappedEar Overlays' Tech HUD layout, and the car's values it shows. */
class TechHudTest {
    private val circuit = FakeCircuit()
    private val track = circuit.track()

    private fun session(drive: FakeCircuit.Drive, extra: (Long) -> List<ChannelValue> = { emptyList() }) =
        drive.fixes.map {
            SampleSet(
                it.tMicros,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, it.lat),
                    ChannelValue(GnssChannels.LON.id, it.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, 120.0),
                ) + extra(it.tMicros),
            )
        }

    @Test
    fun `on a 1080p picture every widget is where Overlays' Tech HUD template puts it`() {
        val l = TechHudLayout(1920, 1080, HudWidget.entries.toSet())
        assertEquals(1f, l.scale)
        assertEquals(HudWidget.entries, l.boxes.keys.toList(), "drawn in the template's order")
        val lap = assertNotNull(l.boxes[HudWidget.Lap])
        assertEquals(0.166f * 1920, lap.left, 0.01f)
        assertEquals(0.829f * 1080, lap.top, 0.01f)
        assertEquals((0.166f + 0.12f) * 1920, lap.right, 0.01f)
        assertEquals((0.829f + 0.16f) * 1080, lap.bottom, 0.01f)
        val map = assertNotNull(l.boxes[HudWidget.TrackMap])
        assertEquals(0.012f * 1920, map.left, 0.01f)
        assertEquals(0.015f * 1080, map.top, 0.01f)
        // Nothing overlaps; the heart rate is top right, the radar above the G readout.
        val boxes = l.boxes.values.toList()
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) assertTrue(!boxes[i].overlaps(boxes[j]), "$i and $j overlap")
        }
        assertTrue(l.boxes.getValue(HudWidget.GRadar).bottom <= l.boxes.getValue(HudWidget.GReadout).top)
        assertEquals(StackPosition.Top, l.stack[HudWidget.Oil])
        assertEquals(StackPosition.Middle, l.stack[HudWidget.Gearbox])
        assertEquals(StackPosition.Bottom, l.stack[HudWidget.Coolant])
        // A 4K picture scales everything; the plates' padding and cuts double.
        val uhd = TechHudLayout(3840, 2160, HudWidget.entries.toSet())
        assertEquals(2f, uhd.scale)
        assertEquals(lap.width * 2, uhd.boxes.getValue(HudWidget.Lap).width, 0.01f)
    }

    @Test
    fun `the widgets sit in two strips, top and bottom, which is all the export draws`() {
        val l = TechHudLayout(1920, 1080, HudWidget.entries.toSet())
        assertEquals(2, l.strips.size)
        val (top, bottom) = l.strips
        assertEquals(16, top.first)
        assertTrue(top.last in 320..345, "top strip ends under the map: $top")
        assertTrue(bottom.first in 650..652, "bottom strip starts at the radar: $bottom")
        assertTrue(bottom.last in 1070..1080, "$bottom")
        // Without the map and the heart rate there is one strip, the bottom.
        val bottomOnly = TechHudLayout(1920, 1080, HudWidget.entries.toSet() - HudWidget.TrackMap - HudWidget.HeartRate)
        assertEquals(listOf(bottom), bottomOnly.strips)
    }

    @Test
    fun `a missing temperature row leaves no hole, the rows below move up and keep one plate`() {
        val l = TechHudLayout(1920, 1080, setOf(HudWidget.Oil, HudWidget.Coolant))
        assertEquals(0.829f * 1080, l.boxes.getValue(HudWidget.Oil).top, 0.01f)
        assertEquals(0.8823f * 1080, l.boxes.getValue(HudWidget.Coolant).top, 0.01f)
        assertEquals(StackPosition.Top, l.stack[HudWidget.Oil])
        assertEquals(StackPosition.Bottom, l.stack[HudWidget.Coolant])
        val single = TechHudLayout(1920, 1080, setOf(HudWidget.Coolant))
        assertEquals(0.829f * 1080, single.boxes.getValue(HudWidget.Coolant).top, 0.01f)
        assertEquals(StackPosition.Single, single.stack[HudWidget.Coolant])
    }

    @Test
    fun `a phone-only drive shows the gauge, lap, G and map, the car's widgets come with its channels`() {
        val drive = circuit.drive(listOf(1.0, 1.0))
        val plain = OverlayData(session(drive), replayLaps("s", session(drive), track))
        assertEquals(
            setOf(HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout, HudWidget.TrackMap),
            hudWidgets(plain),
        )

        val channels = listOf(
            ChannelDef(300, "obd_rpm", "rpm", "obd", 0),
            ChannelDef(301, "obd_steering_angle", "deg", "obd", 1),
            ChannelDef(302, "heart_rate", "bpm", "heart_rate", 0),
            ChannelDef(303, "obd_oil_temp", "°C", "obd", 0),
            ChannelDef(304, "obd_brake_pressure", "bar", "obd", 1),
            // Defined but never sampled: no widget.
            ChannelDef(305, "obd_coolant_temp", "°C", "obd", 0),
        )
        val sets = session(drive) { t ->
            listOf(
                ChannelValue(300, 3000.0 + t / 1_000_000.0),
                ChannelValue(301, -90.0),
                ChannelValue(302, 150.0),
                ChannelValue(303, 110.0),
                ChannelValue(304, 20.0),
            )
        }
        val data = OverlayData(sets, replayLaps("s", sets, track), channels = channels)
        assertEquals(
            setOf(CarValue.Rpm, CarValue.SteeringAngle, CarValue.HeartRate, CarValue.OilTemp),
            data.carValues,
            "the brake pressure in bar is not the brake pedal; a channel without samples is no value",
        )
        assertEquals(
            setOf(
                HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout, HudWidget.TrackMap,
                HudWidget.Steering, HudWidget.HeartRate, HudWidget.Oil,
            ),
            hudWidgets(data),
        )
        val t = drive.fixes[200].tMicros + 20_000
        val car = data.at(t).car
        assertEquals(3000.0 + t / 1_000_000.0, assertNotNull(car.rpm), 0.01)
        assertEquals(-90.0, car.steeringDeg)
        assertEquals(150.0, car.heartRate)
        assertEquals(110.0, car.oilC)
        assertNull(car.brakePct)
        assertNull(car.coolantC)
    }

    @Test
    fun `a temperature polled every 5 s is shown between readings, the engine speed is not held that long`() {
        val drive = circuit.drive(listOf(1.0))
        val channels = listOf(
            ChannelDef(320, "obd_oil_temp", "°C", "obd", 0),
            ChannelDef(321, "obd_rpm", "rpm", "obd", 0),
        )
        val first = drive.fixes.first().tMicros
        val sets = session(drive) { t ->
            val polled = (t - first) % 5_000_000L <= 40_000L
            if (polled) listOf(ChannelValue(320, 100.0), ChannelValue(321, 4000.0)) else emptyList()
        }
        val data = OverlayData(sets, null, channels = channels)
        val between = data.at(first + 2_500_000).car
        assertEquals(100.0, between.oilC)
        assertNull(between.rpm, "an engine speed 5 s apart is a gap")
    }

    @Test
    fun `the pedal tile reads the accelerator pedal before the throttle plate`() {
        val drive = circuit.drive(listOf(1.0))
        val channels = listOf(
            ChannelDef(310, "obd_throttle", "%", "obd", 0),
            ChannelDef(311, "obd_accelerator", "%", "obd", 0),
            ChannelDef(312, "obd_brake", "%", "obd", 0),
        )
        val sets = session(drive) { listOf(ChannelValue(310, 30.0), ChannelValue(311, 80.0), ChannelValue(312, 5.0)) }
        val data = OverlayData(sets, null, channels = channels)
        val car = data.at(drive.fixes[50].tMicros).car
        assertEquals(80.0, car.throttlePct)
        assertEquals(5.0, car.brakePct)
        assertTrue(HudWidget.Pedals in hudWidgets(data))
    }

    @Test
    fun `the map's trail runs from the lap's start, the best lap time and the start line are known`() {
        val drive = circuit.drive(listOf(1.0, 1.05, 0.95))
        val sets = session(drive)
        val laps = replayLaps("s", sets, track)
        val data = OverlayData(sets, laps)
        val best = assertNotNull(laps.best)
        assertEquals(best.lap.endMicros - best.lap.startMicros, data.bestLapMicros)

        val t = best.lap.startMicros + 20_000_000
        val trail = data.trail(t)
        assertTrue(trail.size in 150..601, "${trail.size} points")
        val first = assertNotNull(trail.first())
        val start = assertNotNull(data.at(best.lap.startMicros).position)
        assertTrue((first - start).length() < 1.0, "the trail starts where the lap did")
        assertTrue((assertNotNull(trail.last()) - assertNotNull(data.at(t).position)).length() < 0.01)

        val (a, b) = assertNotNull(data.startLine)
        assertTrue((a - b).length() in 25.0..35.0, "the 30 m start line: ${(a - b).length()}")
        assertNull(OverlayData(sets, null).startLine)
        assertEquals(data.at(t).g, data.gAt(t))
    }
}
