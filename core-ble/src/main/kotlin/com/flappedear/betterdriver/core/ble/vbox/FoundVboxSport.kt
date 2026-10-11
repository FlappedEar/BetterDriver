package com.flappedear.betterdriver.core.ble.vbox

/** A VBOX Sport paired in Android settings. */
data class FoundVboxSport(val name: String, val address: String)

/**
 * Advertised name prefix of a VBOX Sport: RACELOGIC's Android pairing guide shows the device as
 * "VBSport 00000300" (name plus serial number), paired in Android's Bluetooth settings:
 * https://en.racelogic.support/automotive/discontinued-legacy/data-loggers/vbs/kb/pairing-a-vbox-sport-with-android-and-harrys-laptimer/
 */
const val VboxSportNamePrefix = "VBSport"

/**
 * Whether a bonded device is a VBOX Sport: its name starts with [VboxSportNamePrefix] (any case)
 * and it is not LE-only ([leOnly]), since the stream is read over Bluetooth Classic.
 *
 * Transport: RACELOGIC documents only an iOS External Accessory stream (protocol
 * `uk.co.racelogic.nmea`, development portal) and an "Apple certified Bluetooth chip" (VBOX Sport
 * manual). The Android guides pair it in Android's Bluetooth settings (stock Android 4.4.3), which
 * means Bluetooth Classic; that the stream is on the Serial Port Profile is inferred, not stated.
 */
fun isVboxSport(name: String?, leOnly: Boolean): Boolean =
    !leOnly && name?.trimStart()?.startsWith(VboxSportNamePrefix, ignoreCase = true) == true
