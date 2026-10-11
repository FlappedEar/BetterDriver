package com.flappedear.betterdriver.core.ble.nmea

import java.util.UUID

/** Serial Port Profile: the RFCOMM service NMEA receivers (Qstarz, Garmin GLO, XGPS, ...) offer. */
val SppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/** A Bluetooth Classic device paired in Android settings that may be an NMEA receiver. */
data class FoundNmeaDevice(val name: String, val address: String)

/**
 * Major device classes of the Bluetooth Assigned Numbers (Class of Device), the values of
 * android.bluetooth.BluetoothClass.Device.Major. Kept here so the filter is tested on the JVM.
 */
private const val MajorComputer = 0x0100
private const val MajorPhone = 0x0200
private const val MajorAudioVideo = 0x0400

/** Device names of things paired for other reasons that never speak NMEA over SPP. */
private val ExcludedNamePrefixes = listOf("RaceBox", "GoPro")

/**
 * Whether a bonded device can be offered as an NMEA receiver. Deliberately permissive: GNSS
 * receivers report all sorts of device classes (miscellaneous, uncategorised, positioning), and
 * there is no way to ask a device for NMEA without connecting. Only what clearly is something
 * else is left out: RaceBoxes and GoPros (paired over LE for their own sources), BLE-only devices
 * ([leOnly], no RFCOMM), and computers, phones and audio devices by their class.
 * [majorDeviceClass] is null when unknown.
 */
fun isNmeaCandidate(name: String?, majorDeviceClass: Int?, leOnly: Boolean): Boolean =
    !leOnly &&
        ExcludedNamePrefixes.none { name?.startsWith(it, ignoreCase = true) == true } &&
        majorDeviceClass !in setOf(MajorComputer, MajorPhone, MajorAudioVideo)
