package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import com.flappedear.betterdriver.timing.replay.replayLaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FBBD-472: the overlay editor's widget choice and its stored form. */
class HudChoiceTest {
    @Test
    fun everyWidgetIsOnUntilSwitchedOff() {
        HudWidget.entries.forEach { assertTrue(HudChoice.All.isShown(it)) }
        assertEquals("", HudChoice.All.encode())
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
    fun storedFormRoundTripsInTemplateOrder() {
        val c = HudChoice(setOf(HudWidget.TrackMap, HudWidget.Oil))
        assertEquals("Oil,TrackMap", c.encode())
        assertEquals(c, HudChoice.decode(c.encode()))
    }

    @Test
    fun unknownOrEmptyStoredFormIsTolerated() {
        assertEquals(HudChoice.All, HudChoice.decode(null))
        assertEquals(HudChoice.All, HudChoice.decode(" "))
        assertEquals(HudChoice(setOf(HudWidget.Steering)), HudChoice.decode("Gone, Steering ,"))
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
}
