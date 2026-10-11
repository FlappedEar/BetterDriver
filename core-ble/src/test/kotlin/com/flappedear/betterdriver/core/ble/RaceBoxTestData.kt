@file:Suppress("MagicNumber") // Sample bytes.

package com.flappedear.betterdriver.core.ble

/**
 * The example RaceBox Data Message from RaceBox BLE Protocol Description rev 8, page 7 (sent by a
 * RaceBox Mini: byte 67 is a battery level). Not a capture from our own Micro: add captured packets
 * once the Micro has been logged (FBBD-46).
 */
object RaceBoxTestData {
    private const val OfficialExample =
        "B5 62 FF 01 50 00 A0 E7 0C 07 E6 07 01 0A 08 33 " +
            "08 37 19 00 00 00 2A AD 4D 0E 03 01 EA 0B C6 93 " +
            "E1 0D 3B 37 6F 19 61 8C 09 00 0F 01 09 00 9C 03 " +
            "00 00 2C 07 00 00 23 00 00 00 00 00 00 00 D0 00 " +
            "00 00 88 A9 DD 00 2C 01 00 59 FD FF 71 00 CE 03 " +
            "2F FF 56 00 FC FF 06 DB"

    /** The whole example packet, header and checksum included. */
    val officialPacket: ByteArray = OfficialExample.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    fun payload(): ByteArray = officialPacket.copyOfRange(6, 6 + RaceBoxDataLength)

    /** The example payload, optionally edited, in a packet with a recomputed checksum. */
    fun packet(edit: (ByteArray) -> Unit = {}): ByteArray =
        ubxPacket(RaceBoxClass, RaceBoxDataId, payload().also(edit))

    /** The example as a Micro would send it: byte 67 = 0x79, 12.1 V (the document's own example value). */
    fun microPacket(edit: (ByteArray) -> Unit = {}): ByteArray = packet { it[67] = 0x79; edit(it) }
}
