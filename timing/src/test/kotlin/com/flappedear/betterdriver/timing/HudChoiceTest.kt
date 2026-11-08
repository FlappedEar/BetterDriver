package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.HudValue
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.StackPosition
import com.flappedear.betterdriver.timing.overlay.TechHudShares
import com.flappedear.betterdriver.timing.overlay.hudValueText
import com.flappedear.betterdriver.timing.overlay.hudValues
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-472: the overlay editor's widget choice and its stored form. */
class HudChoiceTest {
    @Test
    fun everyWidgetIsOnUntilSwitchedOff() {
        HudWidget.entries.forEach { assertTrue(HudChoice.All.isShown(it)) }
        assertEquals(HudChoice.All, HudChoice.decode(HudChoice.All.encode()))
        assertEquals(HudWidget.entries.size, HudChoice.All.shownCount())
    }

    @Test
    fun switchingOffAndOnAgain() {
        val off = HudChoice.All.with(HudWidget.Oil, shown = false).with(HudWidget.GReadout, shown = false)
        assertFalse(off.isShown(HudWidget.Oil))
        assertTrue(off.isShown(HudWidget.Lap))
        assertEquals(HudWidget.entries.size - 2, off.shownCount())
        assertEquals(HudChoice.All, off.with(HudWidget.Oil, true).with(HudWidget.GReadout, true))
    }

    @Test
    fun storedFormRoundTrips() {
        val c = HudChoice(setOf(HudWidget.TrackMap, HudWidget.Oil))
            .move(HudWidget.HeartRate, 0.0f, 0.4f, all)
            .withValue("obd_oil_pressure", "OIL P", "bar", all)
        assertEquals(c, HudChoice.decode(c.encode()))
    }

    @Test
    fun unknownOrBrokenStoredFormIsTolerated() {
        assertEquals(HudChoice.All, HudChoice.decode(null))
        assertEquals(HudChoice.All, HudChoice.decode(" "))
        assertEquals(HudChoice.All, HudChoice.decode("not json"))
        assertEquals(
            HudChoice(setOf(HudWidget.Steering)),
            HudChoice.decode("""{"hidden":["Gone","Steering"],"places":{"Gone":{"x":0.1,"y":0.1}},"later":1}"""),
        )
    }

    @Test
    fun shownCountOnlyCountsWhatTheSessionHas() {
        val c = HudChoice(setOf(HudWidget.Oil, HudWidget.Steering))
        assertEquals(1, c.shownCount(listOf(HudWidget.Oil, HudWidget.Lap)))
    }

    @Test
    fun `a switched-off widget is not drawn, one without data stays out whatever the switch`() {
        val circuit = FakeCircuit()
        val sets = circuit.drive(listOf(1.0, 1.0)).fixes.map {
            SampleSet(
                it.tMicros,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, it.lat),
                    ChannelValue(GnssChannels.LON.id, it.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, 120.0),
                ),
            )
        }
        val data = OverlayData(sets, replayLaps("s", sets, circuit.track()))
        assertEquals(hudWidgets(data), hudWidgets(data, HudChoice.All))
        val choice = HudChoice(setOf(HudWidget.TrackMap, HudWidget.Steering))
        val shown = hudWidgets(data, choice)
        assertEquals(setOf(HudWidget.Tachometer, HudWidget.Lap, HudWidget.GRadar, HudWidget.GReadout), shown)
        assertFalse(HudWidget.TrackMap in TechHudLayout(1920, 1080, shown).boxes)
    }

    private val all = HudWidget.entries.toSet()

    @Test
    fun `a moved widget snaps to the grid, stays inside and never covers another`() {
        val c = HudChoice.All.move(HudWidget.TrackMap, 0.4113f, 0.0f, all)
        val spot = c.places.getValue(HudWidget.TrackMap)
        assertEquals(33f / HudChoice.GridX, spot.x, 1e-6f)
        assertEquals(0f, spot.y)
        // Off the right edge: held inside.
        val edge = HudChoice.All.move(HudWidget.HeartRate, 0.99f, 0.35f, all)
        assertTrue(edge.places.getValue(HudWidget.HeartRate).x + HudWidget.HeartRate.width <= 1f + 1e-6f)
        // Onto the lap tile: refused, nothing moves.
        assertEquals(HudChoice.All, HudChoice.All.move(HudWidget.HeartRate, 0.17f, 0.83f, all))
        // Onto a widget that is switched off: allowed.
        val shown = all - HudWidget.Lap
        assertTrue(HudWidget.HeartRate in HudChoice.All.move(HudWidget.HeartRate, 0.17f, 0.83f, shown).places)
        assertFalse(HudChoice.All.moved)
        assertTrue(c.moved)
        assertEquals(HudChoice.All, c.reset())
    }

    @Test
    fun `a moved row is a plate of its own, the others still stack`() {
        val c = HudChoice.All.move(HudWidget.Gearbox, 0.2f, 0.4f, all)
        val shares = TechHudShares(all, c)
        assertEquals(StackPosition.Top, shares.stack[HudWidget.Oil])
        assertEquals(StackPosition.Bottom, shares.stack[HudWidget.Coolant])
        assertNull(shares.stack[HudWidget.Gearbox])
        assertEquals(HudWidget.Gearbox.y, shares.widgets.getValue(HudWidget.Coolant).top, 1e-6f)
    }

    @Test
    fun `value plates fill the top row, at most five, each channel once`() {
        var c = HudChoice.All
        for (i in 1..7) c = c.withValue("ch$i", "C$i", "V", all)
        assertEquals(HudValue.Max, c.values.size)
        assertEquals(c, c.withValue("ch1", "again", "", all))
        val boxes = c.values.map { it.share }
        val widgets = TechHudShares(all, HudChoice.All).widgets.values
        boxes.forEachIndexed { i, b ->
            assertEquals(0.015f, b.top, 1e-6f)
            assertTrue(widgets.none { it.overlaps(b) }, "plate $i covers a widget")
            assertTrue(boxes.filterIndexed { j, _ -> j != i }.none { it.overlaps(b) })
        }
        val renamed = c.renameValue("ch2", "BOOST")
        assertEquals("BOOST", renamed.values[1].label)
        assertEquals(4, c.withoutValue("ch3").values.size)
        // Moving a plate onto the map is refused; into free road it goes.
        assertEquals(c, c.moveValue(0, 0.0f, 0.0f, all))
        assertEquals(0.4f, c.moveValue(0, 0.4f, 0.4f, all).values[0].y, 1f / HudChoice.GridY)
        assertFalse(c.moved)
        assertTrue(c.moveValue(0, 0.4f, 0.4f, all).moved)
        assertEquals(c, c.moveValue(0, 0.4f, 0.4f, all).reset())
    }

    @Test
    fun `a plate shows its channel's reading, a channel the session lacks is not drawn`() {
        val circuit = FakeCircuit()
        val sets = circuit.drive(listOf(1.0)).fixes.map {
            SampleSet(
                it.tMicros,
                listOf(
                    ChannelValue(GnssChannels.LAT.id, it.lat),
                    ChannelValue(GnssChannels.LON.id, it.lon),
                    ChannelValue(GnssChannels.SPEED_KMH.id, 120.0),
                    ChannelValue(310, 3.8),
                ),
            )
        }
        val channels = listOf(
            ChannelDef(310, "obd_oil_pressure", "bar", "obd", 2),
            ChannelDef(311, "boost", "bar", "obd", 2),
        )
        val data = OverlayData(sets, replayLaps("s", sets, circuit.track()), channels = channels)
        assertEquals(listOf("obd_oil_pressure"), data.recordedChannels.map { it.name })
        val c = HudChoice.All.withValue("obd_oil_pressure", "OIL P", "bar", all).withValue("boost", "BOOST", "bar", all)
        assertEquals(listOf("obd_oil_pressure"), hudValues(data, c).map { it.channel })
        assertEquals(1, TechHudLayout(1920, 1080, hudWidgets(data, c), c.drawnOver(data)).values.size)
        assertEquals("3.80", hudValueText(data.valueAt("obd_oil_pressure", sets[5].tMicros)))
        assertEquals("—", hudValueText(data.valueAt("boost", sets[5].tMicros)))
        assertEquals("13.9", hudValueText(13.94))
        assertEquals("142", hudValueText(141.6))
    }
}
