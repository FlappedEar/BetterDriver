package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.timing.overlay.HudChoice
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayEditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FBBD-472: the overlay editor as the iOS screen drives it. */
class OverlayEditorStateTest {
    private val channels = listOf(
        ChannelDef(310, "obd_oil_pressure", "bar", "obd", 2),
        ChannelDef(311, "obd_battery_voltage", "V", "obd", 2),
    )

    @Test
    fun fromSettingsEveryWidgetHasDataAndNoValueCanBeAdded() {
        val s = OverlayEditorState(null)
        assertFalse(s.hasSession)
        assertTrue(s.rows.all { it.on && it.hasData })
        assertEquals(HudWidget.entries.size, s.plates.size)
        assertFalse(s.canAdd)
        assertFalse(s.canReset)
        assertEquals("${HudWidget.entries.size} of ${HudWidget.entries.size} widgets", s.summary)
    }

    @Test
    fun aSessionDimsWhatItLacksAndOffersItsChannels() {
        val available = HudWidget.entries.toSet() - HudWidget.Steering
        val s = OverlayEditorState("", available, channels)
        assertFalse(s.rows.first { it.widget == HudWidget.Steering }.hasData)
        assertTrue(s.plates.none { it.widget == HudWidget.Steering })
        assertEquals(listOf("obd_oil_pressure", "obd_battery_voltage"), s.addable.map { it.name })
        s.add("obd_oil_pressure")
        assertEquals("OIL PRESSU", s.values.single().label)
        assertEquals(listOf("obd_battery_voltage"), s.addable.map { it.name })
        s.rename("obd_oil_pressure", " oil p ")
        assertEquals("OIL P", s.values.single().label)
        assertTrue(s.plates.any { it.key == "v-obd_oil_pressure" && it.valueIndex == 0 })
        s.remove("obd_oil_pressure")
        assertTrue(s.values.isEmpty())
    }

    @Test
    fun switchesDropsAndResetChangeTheStoredChoice() {
        val s = OverlayEditorState(null)
        s.toggle(HudWidget.GReadout, false)
        assertFalse(HudChoice.decode(s.stored).isShown(HudWidget.GReadout))
        assertTrue(s.plates.none { it.widget == HudWidget.GReadout })
        s.drop("w-HeartRate", 0.89f, 0.35f)
        assertTrue(s.canReset)
        assertTrue(HudWidget.HeartRate in HudChoice.decode(s.stored).places)
        s.reset()
        assertFalse(s.canReset)
        assertFalse(HudChoice.decode(s.stored).isShown(HudWidget.GReadout), "reset keeps the switches")
    }
}
