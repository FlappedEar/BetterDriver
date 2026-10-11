package com.flappedear.betterdriver.core.ble.hr

import com.flappedear.betterdriver.core.ble.LeReader
import com.flappedear.betterdriver.core.ble.TruncatedValue

/** Bits of the Flags field (uint8). */
private const val Value16Bit = 1 shl 0
private const val ContactShift = 1
private const val EnergyPresent = 1 shl 3
private const val RrPresent = 1 shl 4
private const val TwoBits = 0b11
private const val RrBytes = 2

/** RR-Intervals are in 1/1024 s. */
private const val RrUnitsPerSecond = 1024.0
private const val MsPerSecond = 1000.0

/** Sensor Contact Status (Flags bits 1 and 2). */
enum class SensorContact {
    /** Values 0 and 1: the sensor cannot tell. */
    NOT_SUPPORTED,

    /** Value 2: supported, no skin contact; the heart rate is then not meaningful. */
    NOT_DETECTED,

    /** Value 3: supported and in contact. */
    DETECTED,
}

/**
 * One Heart Rate Measurement value (characteristic 0x2A37 of the Heart Rate Service 0x180D).
 *
 * Layout (little-endian, in this order):
 *
 * | Field | Format | Unit | Present when |
 * | --- | --- | --- | --- |
 * | Flags | uint8 | | always |
 * | Heart Rate Measurement Value | uint8, or uint16 if flag bit 0 | beats per minute | always |
 * | Energy Expended | uint16 | kJ | flag bit 3 |
 * | RR-Interval × n | uint16 each | 1/1024 s | flag bit 4; as many as fit, oldest first |
 *
 * Flag bits 1–2 are the Sensor Contact Status ([SensorContact]).
 *
 * Sources: Bluetooth SIG GATT Specification Supplement, section "Heart Rate Measurement"
 * (https://www.bluetooth.com/specifications/gss/), Heart Rate Service 1.0
 * (https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/), and the Bluetooth SIG
 * GATT XML org.bluetooth.characteristic.heart_rate_measurement
 * (https://github.com/oesmith/gatt-xml/blob/master/org.bluetooth.characteristic.heart_rate_measurement.xml).
 */
data class HeartRateMeasurement(
    val flags: Int,
    val bpm: Int,
    val energyKj: Int? = null,
    /** RR-Intervals in milliseconds, oldest first; empty when none were sent. */
    val rrMs: List<Double> = emptyList(),
) {
    val contact: SensorContact
        get() = when ((flags shr ContactShift) and TwoBits) {
            2 -> SensorContact.NOT_DETECTED
            TwoBits -> SensorContact.DETECTED
            else -> SensorContact.NOT_SUPPORTED
        }

    companion object {
        /**
         * Decodes one value; null if it is shorter than its flags say. A trailing odd byte where
         * an RR-Interval would be is dropped, as it cannot be half an interval.
         */
        fun parse(bytes: ByteArray): HeartRateMeasurement? = try {
            read(LeReader(bytes))
        } catch (_: TruncatedValue) {
            null
        }

        private fun read(r: LeReader): HeartRateMeasurement {
            val flags = r.uint8()
            val bpm = if (flags and Value16Bit != 0) r.uint16() else r.uint8()
            val energy = if (flags and EnergyPresent != 0) r.uint16() else null
            val rr = if (flags and RrPresent != 0) {
                List(r.remaining / RrBytes) { r.uint16() / RrUnitsPerSecond * MsPerSecond }
            } else {
                emptyList()
            }
            return HeartRateMeasurement(flags, bpm, energy, rr)
        }
    }
}
