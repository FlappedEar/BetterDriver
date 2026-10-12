package com.flappedear.betterdriver.core.ble

import org.junit.Assert.assertEquals
import org.junit.Test

private data class Heard(val name: String, val address: String, val rssi: Int)

class NearbyListTest {
    private fun list() = NearbyList(Heard::address, Heard::rssi)

    @Test
    fun listsStrongestFirstAndKeepsTheLatestOfEachAddress() {
        val nearby = list()
        nearby.heard(Heard("A", "AA", -80), 0)
        nearby.heard(Heard("B", "BB", -60), 10)
        nearby.heard(Heard("A", "AA", -50), 20)
        assertEquals(listOf("AA", "BB"), nearby.current(30).map { it.address })
        assertEquals(-50, nearby.current(30).first().rssi)
    }

    @Test
    fun forgetsADeviceNotHeardFor5Seconds() {
        val nearby = list()
        nearby.heard(Heard("A", "AA", -80), 0)
        nearby.heard(Heard("B", "BB", -60), 3_000)
        assertEquals(2, nearby.current(ForgetNearbyAfterMs).size)
        assertEquals(listOf("BB"), nearby.current(ForgetNearbyAfterMs + 1).map { it.address })
        assertEquals(emptyList<Heard>(), nearby.current(9_000))
    }
}
