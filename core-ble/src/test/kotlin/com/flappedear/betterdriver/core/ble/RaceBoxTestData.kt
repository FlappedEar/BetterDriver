@file:Suppress("MagicNumber") // Byte offsets and sample values.

package com.flappedear.betterdriver.core.ble

/**
 * A RaceBox Data Message payload built from the example field bytes quoted in the reference client
 * (lademeister/ESP32-RaceBox, ESP32_RaceBox_BLE_client.ino, parse_RaceBox_Data_Message_payload).
 * Not a capture from our own device: replace with captured packets once the Micro has been
 * logged (FBBD-46).
 */
object RaceBoxTestData {
    private val fields: List<Pair<Int, String>> = listOf(
        0 to "A0 E7 0C 07", // iTOW
        4 to "E6 07", // year 2022
        6 to "01", // month
        7 to "0A", // day
        8 to "08", // hour
        9 to "33", // minute
        10 to "08", // second
        11 to "37", // validity: date and time valid
        12 to "19 00 00 00", // time accuracy 25 ns
        16 to "2A AD 4D 0E", // nanoseconds 239971626
        20 to "03", // fix type 3D
        21 to "01", // fix flags: fix OK, heading not valid
        22 to "EA", // date/time flags
        23 to "0B", // 11 satellites
        24 to "C6 93 E1 0D", // longitude 23.2887238
        28 to "3B 37 6F 19", // latitude 42.6719035
        32 to "61 8C 09 00", // WGS altitude 625.761 m
        36 to "0F 01 09 00", // MSL altitude 590.095 m
        40 to "9C 03 00 00", // horizontal accuracy 0.924 m
        44 to "2C 07 00 00", // vertical accuracy 1.836 m
        48 to "23 00 00 00", // speed 35 mm/s
        52 to "00 00 00 00", // heading 0
        56 to "D0 00 00 00", // speed accuracy 208 mm/s
        60 to "88 A9 DD 00", // heading accuracy 145.26856 deg
        64 to "2C 01", // PDOP 3.00
        66 to "00", // coordinates valid
        67 to "7B", // Micro: 12.3 V
        68 to "FD FF", // accel X -0.003 g
        70 to "71 00", // accel Y 0.113 g
        72 to "CE 03", // accel Z 0.974 g
        74 to "64 00", // gyro X 1.00 deg/s
        76 to "38 FF", // gyro Y -2.00 deg/s
        78 to "2C 01", // gyro Z 3.00 deg/s
    )

    fun payload(): ByteArray {
        val out = ByteArray(RaceBoxDataLength)
        for ((offset, hex) in fields) {
            hex.split(' ').forEachIndexed { i, b -> out[offset + i] = b.toInt(16).toByte() }
        }
        return out
    }

    fun packet(edit: (ByteArray) -> Unit = {}): ByteArray =
        ubxPacket(RaceBoxClass, RaceBoxDataId, payload().also(edit))
}
