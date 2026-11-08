@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.timing

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import com.flappedear.betterdriver.timing.overlay.HudWeather
import com.flappedear.betterdriver.timing.overlay.HudWidget
import com.flappedear.betterdriver.timing.overlay.OverlayData
import com.flappedear.betterdriver.timing.overlay.OverlayWeather
import com.flappedear.betterdriver.timing.overlay.Sky
import com.flappedear.betterdriver.timing.overlay.TechHudLayout
import com.flappedear.betterdriver.timing.overlay.TrackSurface
import com.flappedear.betterdriver.timing.overlay.hudWidgets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FBBD-471: the weather plate of the Tech HUD (UI-320 B, UI-321 B). */
class HudWeatherTest {
    private val quarter = 15 * 60 * 1_000_000L
    private val dry = HudWeather(9.4, Sky.PartlyCloudy, TrackSurface.Dry, 12.4, 270.0, 78.2)
    private val wet = HudWeather(6.6, Sky.Rain, TrackSurface.Wet, 20.0, 180.0, 95.0)

    private fun sets() = (0..100).map {
        SampleSet(
            it * 100_000L,
            listOf(
                ChannelValue(GnssChannels.LAT.id, 40.0 + it * 1e-5),
                ChannelValue(GnssChannels.LON.id, -30.0),
                ChannelValue(GnssChannels.SPEED_KMH.id, 100.0),
            ),
        )
    }

    @Test
    fun `the plate sits left of heart rate, and takes heart rate's place when there is none`() {
        val with = TechHudLayout(1920, 1080, setOf(HudWidget.HeartRate, HudWidget.Weather))
        val w = with.boxes.getValue(HudWidget.Weather)
        val hr = with.boxes.getValue(HudWidget.HeartRate)
        assertEquals(0.78f * 1920, w.left, 0.01f)
        assertEquals(hr.top, w.top, 0.01f)
        assertEquals(hr.height, w.height, 0.01f)
        assertTrue(w.right < hr.left, "left of heart rate, not over it")

        val alone = TechHudLayout(1920, 1080, setOf(HudWidget.Weather)).boxes.getValue(HudWidget.Weather)
        assertEquals(hr, alone)
    }

    @Test
    fun `the plate is shown only for a session with weather, and each moment has its quarter hour`() {
        assertTrue(HudWidget.Weather !in hudWidgets(OverlayData(sets(), null)))
        assertTrue(HudWidget.Weather !in hudWidgets(OverlayData(sets(), null, weather = OverlayWeather(emptyList()))))

        val weather = OverlayWeather(listOf(quarter to wet, -quarter / 3 to dry))
        val data = OverlayData(sets(), null, weather = weather)
        assertTrue(HudWidget.Weather in hudWidgets(data))
        assertEquals(dry, data.at(0).weather, "the quarter hour the start falls in")
        assertEquals(dry, data.at(quarter - 1).weather)
        assertEquals(wet, data.at(quarter).weather)
        assertEquals(dry, OverlayWeather(listOf(10L to dry)).at(0), "before the first value, the first")
        assertNull(OverlayData(sets(), null).at(0).weather)
    }

    @Test
    fun `the plate's texts`() {
        assertEquals("9°", dry.airText)
        assertEquals("12 km/h", dry.windText)
        assertEquals("78%", dry.humidityText)
        assertEquals("TRACK DRY", dry.surface?.text)
        assertEquals(90.0, dry.windArrowDeg, "a west wind blows to the east")
        assertEquals("—", HudWeather().airText)
        assertNull(HudWeather().windText)
        assertNull(HudWeather().humidityText)
        assertNull(HudWeather(windKmh = 0.4, windFromDeg = 90.0).windArrowDeg, "calm: no arrow")
    }

    @Test
    fun `the WMO codes map to the sky the plate draws`() {
        assertEquals(Sky.Clear, Sky.of(0))
        assertEquals(Sky.Clear, Sky.of(1))
        assertEquals(Sky.PartlyCloudy, Sky.of(2))
        assertEquals(Sky.Cloudy, Sky.of(3))
        assertEquals(Sky.Fog, Sky.of(45))
        assertEquals(Sky.Rain, Sky.of(53))
        assertEquals(Sky.Rain, Sky.of(81))
        assertEquals(Sky.Snow, Sky.of(73))
        assertEquals(Sky.Thunder, Sky.of(95))
        assertNull(Sky.of(4))
    }
}
