@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.obd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObdStatusTrackerTest {
    private val rpm = StandardPids.Rpm
    private val fuel = StandardPids.FuelLevel

    @Test
    fun `values and rates of a connected car`() {
        val t = ObdStatusTracker(windowMs = 5_000)
        t.connecting("vLinker MC+")
        assertEquals(ObdLinkState.Connecting, t.snapshot(0).state)
        t.ready("ELM327 v1.5", "A6", "12.4V", listOf(rpm, fuel), nowMs = 1_000)
        // RPM every 100 ms for 5 s; fuel level once.
        for (i in 1..50) t.value(rpm, 800.0 + i, 1_000L + i * 100)
        t.value(fuel, 62.0, 1_500)
        val s = t.snapshot(6_000)
        assertEquals(ObdLinkState.Connected, s.state)
        assertEquals("vLinker MC+ · ELM327 v1.5 · ISO 15765-4 CAN (11 bit, 500 kbaud) · 12.4V", s.detail)
        assertEquals(listOf("rpm", "fuel_level"), s.readings.map { it.id })
        assertEquals(850.0, s.readings[0].value)
        assertEquals(10.0, s.readings[0].hz, 0.01)
        assertEquals(0.2, s.readings[1].hz, 0.01)
    }

    @Test
    fun `the rate right after connecting is not diluted by the window`() {
        val t = ObdStatusTracker(windowMs = 5_000)
        t.ready("ELM327", "A6", null, listOf(rpm), nowMs = 0)
        for (i in 1..10) t.value(rpm, 900.0, i * 100L)
        assertEquals(10.0, t.snapshot(1_000).readings.single().hz, 0.01)
    }

    @Test
    fun `a channel the car never answered is left out, and an ended connection clears values`() {
        val t = ObdStatusTracker()
        t.ready("ELM327", "A6", null, listOf(rpm, fuel), nowMs = 0)
        t.value(rpm, 900.0, 100)
        assertEquals(listOf("rpm"), t.snapshot(200).readings.map { it.id })
        t.ended("car stopped answering")
        val s = t.snapshot(300)
        assertEquals(ObdLinkState.Searching, s.state)
        assertTrue(s.readings.isEmpty())
        assertEquals("Connection ended: car stopped answering", s.detail)
    }

    @Test
    fun `rate after a long run with no snapshots read`() {
        val t = ObdStatusTracker(windowMs = 1_000)
        t.ready("ELM327", "A6", null, listOf(rpm), nowMs = 0)
        for (i in 1..100_000) t.value(rpm, 900.0, i * 100L)
        assertEquals(10.0, t.snapshot(10_000_050).readings.single().hz, 0.01)
    }

    @Test
    fun `value text`() {
        assertEquals("2851 rpm", ObdReading("rpm", "RPM", "rpm", 2851.25, 9.0).valueText())
        assertEquals("34.1 %", ObdReading("throttle", "Throttle", "%", 34.12, 9.0).valueText())
        assertEquals("-3.5", ObdReading("x", "X", "", -3.5, 1.0).valueText())
    }

    @Test
    fun `protocol names`() {
        assertEquals("ISO 15765-4 CAN (11 bit, 500 kbaud)", ObdStatusTracker.protocolName("A6"))
        assertEquals("ISO 9141-2", ObdStatusTracker.protocolName("3"))
        assertEquals("protocol X", ObdStatusTracker.protocolName("X"))
        assertEquals(null, ObdStatusTracker.protocolName(""))
    }
}
