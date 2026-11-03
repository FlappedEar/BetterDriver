package com.flappedear.betterdriver.recorder.live

import com.flappedear.betterdriver.core.ble.DiagRow
import com.flappedear.betterdriver.recorder.ChannelDef
import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.GnssChannels
import com.flappedear.betterdriver.recorder.SampleSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An Equipment page's live data (FBBD-440): what a device's source sends, as the page shows it. */
class EquipmentLiveDataTest {
    private var now = 1_000L
    private val live = EquipmentLiveData { now }

    private val heart = ChannelDef(400, "heart_rate", "bpm", "heart_rate", 0)
    private val rr = ChannelDef(401, "hr_rr_interval", "ms", "heart_rate", 1)

    private fun send(vararg values: Pair<ChannelDef, Double>) =
        live.submit(SampleSet(live.nowMicros(), values.map { (d, v) -> ChannelValue(d.id, v) }))

    @Test
    fun connectingUntilTheFirstValueWithTheLastEvent() {
        val s = live.snapshot()
        assertNull(s.sinceLastMs)
        assertEquals("Connecting…", EquipmentLiveText.header("Polar H10", s).title)
        live.event("hr_connected", "address=AA name=Polar H10")
        val h = EquipmentLiveText.header("Polar H10", live.snapshot())
        assertFalse(h.ok)
        assertEquals("Polar H10 · hr connected: address=AA name=Polar H10", h.detail)
    }

    @Test
    fun keepsTheNewestValueOfEachChannelAndTheRate() {
        live.defineChannel(heart)
        live.defineChannel(rr)
        repeat(5) { i ->
            send(heart to 120.0 + i, rr to 500.0)
            now += 1_000
        }
        now -= 1_000
        val s = live.snapshot()
        assertEquals(listOf(124.0, 500.0), s.channels.map { it.value })
        assertEquals(5L, s.sets)
        assertEquals(1.0, s.rateHz, 0.01)
        assertEquals(0L, s.sinceLastMs)
        val h = EquipmentLiveText.header("Polar H10", s)
        assertTrue(h.ok)
        assertEquals("Receiving · 1.0 Hz", h.title)
        assertEquals("Polar H10 · 2 values", h.detail)
        val sections = EquipmentLiveText.sections(s) { "-" }
        assertEquals(listOf("Heart rate", "Link"), sections.map { it.title })
        assertEquals(listOf(DiagRow("Heart rate", "124 bpm"), DiagRow("RR interval", "500.0 ms")), sections[0].rows)
    }

    @Test
    fun silentDeviceTurnsAmber() {
        live.defineChannel(heart)
        send(heart to 80.0)
        now += 6_000
        val h = EquipmentLiveText.header("Strap", live.snapshot())
        assertFalse(h.ok)
        assertEquals("No data for 6 s", h.title)
    }

    @Test
    fun gnssValuesReadForPeople() {
        GnssChannels.ALL.forEach(live::defineChannel)
        live.defineChannel(ChannelDef(202, "nmea_hdop", "", "nmea", 2))
        live.defineChannel(ChannelDef(904, "tyre_temp_fl", "°C", "tpms", 0))
        send(
            GnssChannels.UTC_MS to 1_700_000_000_000.0,
            GnssChannels.LAT to -33.5,
            GnssChannels.SPEED_KMH to 101.25,
            GnssChannels.SATS to 14.0,
        )
        live.submit(SampleSet(0, listOf(ChannelValue(202, 0.9), ChannelValue(904, 31.0))))
        val sections = EquipmentLiveText.sections(live.snapshot()) { "clock $it" }
        assertEquals(listOf("Position", "Receiver", "Tyres", "Link"), sections.map { it.title })
        assertEquals(
            listOf(
                DiagRow("Time (UTC)", "clock 1700000000000"),
                DiagRow("Latitude", "−33.50000000°"),
                DiagRow("Speed", "101.250 km/h"),
                DiagRow("Satellites", "14"),
            ),
            sections[0].rows,
        )
        assertEquals(DiagRow("HDOP", "0.90"), sections[1].rows.single())
        assertEquals(DiagRow("Temperature FL", "31 °C"), sections[2].rows.single())
    }

    @Test
    fun undefinedChannelAndEventsNewestFirst() {
        live.submit(SampleSet(0, listOf(ChannelValue(77, 1.0))))
        live.event("a", "first")
        now += 65_000
        live.event("b", "")
        val s = live.snapshot()
        assertEquals("Channel 77", EquipmentLiveText.label(s.channels.single().def))
        val events = EquipmentLiveText.sections(s) { "" }.last()
        assertEquals("Events", events.title)
        assertEquals(listOf(DiagRow("b", "1 min 05 s"), DiagRow("a: first", "0 s")), events.rows)
    }

    @Test
    fun resetForgetsValues() {
        live.defineChannel(heart)
        send(heart to 90.0)
        live.reset()
        val s = live.snapshot()
        assertTrue(s.channels.isEmpty())
        assertEquals(0L, s.sets)
        assertEquals(DiagRow("Updates", "None yet"), EquipmentLiveText.sections(s) { "" }.single().rows.first())
    }

    @Test
    fun labels() {
        fun l(name: String) = EquipmentLiveText.label(ChannelDef(1, name, "", "x"))
        assertEquals("Accel X", l("rc_accel_x"))
        assertEquals("Pressure RR", l("tyre_pressure_rr"))
        assertEquals("Speed accuracy", l("dragy_speed_acc"))
        assertEquals("Horizontal accuracy", l("dragy_hacc"))
        assertEquals("Fix quality", l("diy_fix_quality"))
        assertEquals("Compass heading", l("lns_compass_heading"))
        assertEquals("GPS accuracy", l("phone_gps_accuracy"))
    }
}
