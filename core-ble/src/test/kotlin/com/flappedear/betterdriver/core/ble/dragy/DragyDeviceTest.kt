@file:Suppress("MagicNumber")

package com.flappedear.betterdriver.core.ble.dragy

import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DragyDeviceTest {
    @Test
    fun `handshake answers a, b, a xor b, a and b`() {
        val challenge = byteArrayOf(0x5A, 0xC3.toByte(), 0x11)
        assertArrayEquals(byteArrayOf(0x5A, 0xC3.toByte(), 0x99.toByte(), 0x42), handshakeResponse(challenge))
        assertNull(handshakeResponse(byteArrayOf(1)))
        assertNull(handshakeResponse(ByteArray(0)))
    }

    @Test
    fun `profile subscribes to FD02 and answers on FD03`() {
        val p = DragyGatt.PROFILE
        assertEquals(UUID.fromString("0000fd00-0000-1000-8000-00805f9b34fb"), p.service)
        assertEquals(listOf(UUID.fromString("0000fd02-0000-1000-8000-00805f9b34fb")), p.notify)
        assertTrue(p.optional.isEmpty())
        assertEquals(UUID.fromString("0000fd03-0000-1000-8000-00805f9b34fb"), p.handshake!!.characteristic)
        assertArrayEquals(byteArrayOf(3, 5, 6, 1), p.handshake!!.respond(byteArrayOf(3, 5)))
        assertSame(DragyGatt.SERVICE, p.service)
    }

    @Test
    fun `scan filter matches names, the FD00 service or manufacturer data`() {
        assertTrue(isDragy("DRG70-ABC123"))
        assertTrue(isDragy("drg69-x"))
        assertTrue(isDragy("My Dragy Pro"))
        assertTrue(isDragy(null, listOf(DragyGatt.SERVICE)))
        assertTrue(isDragy("", manufacturerData = "xxDRAGYxx".toByteArray()))
    }

    @Test
    fun `scan filter rejects other devices`() {
        assertFalse(isDragy("RaceBox Mini 1234567890"))
        assertFalse(isDragy(null, listOf(UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))))
        assertFalse(isDragy(null, manufacturerData = byteArrayOf(1, 2, 3)))
        assertFalse(isDragy(null))
    }
}
