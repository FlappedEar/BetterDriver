package com.flappedear.betterdriver.core.ble

import java.io.File
import java.io.IOException

/**
 * Motion-sensor calibrations, one small file per RaceBox address, in a directory both the UI and
 * the recorder process read (FBBD-89). A file is replaced whole, so a reader never sees half of one.
 */
class ImuCalibrationStore(private val dir: File) {
    fun load(address: String): ImuCalibration? = try {
        file(address).takeIf { it.isFile }?.readText()?.let(ImuCalibration::decode)
    } catch (_: IOException) {
        null
    }

    /** Throws [IOException] if it cannot be written. */
    fun save(address: String, calibration: ImuCalibration) {
        dir.mkdirs()
        val tmp = File(dir, "${name(address)}.tmp")
        tmp.writeText(calibration.encode())
        if (!tmp.renameTo(file(address))) {
            tmp.delete()
            throw IOException("could not save the calibration")
        }
    }

    fun clear(address: String) {
        file(address).delete()
    }

    private fun file(address: String) = File(dir, "${name(address)}.txt")

    /** "AA:BB:CC:DD:EE:FF" → "AABBCCDDEEFF". */
    private fun name(address: String) = address.filter { it.isLetterOrDigit() }.uppercase()
}
