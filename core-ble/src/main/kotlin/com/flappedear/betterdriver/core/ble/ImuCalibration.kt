@file:Suppress("MagicNumber") // Indices of the 3×3 matrix and of the stored fields.

package com.flappedear.betterdriver.core.ble

import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A 3-vector in the RaceBox frame: X front/back, Y right/left, Z up/down (protocol rev 8, p. 6). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    val length: Double get() = sqrt(x * x + y * y + z * z)
}

/** One still sample taken for calibration: acceleration in g, rotation rate in deg/s. */
data class ImuSample(val accel: Vec3, val gyro: Vec3)

/**
 * App-side calibration of the RaceBox motion sensors (FBBD-89). The RaceBox protocol has no
 * calibration command; RaceBox leaves "zero-offset calibration, and orientation adjustments" to the
 * app (protocol rev 8, p. 22–23). Taken with the car standing still:
 * - [gyroBias]: the mean rotation rate at rest, subtracted from every gyro reading;
 * - [level]: a rotation that turns the measured gravity into straight down the Z axis, so a RaceBox
 *   mounted at a tilt reads as if it were level. Applied to acceleration and rotation alike.
 * It cannot see a mount turned about the vertical (yaw); the RaceBox's X axis must point forward.
 */
data class ImuCalibration(
    val gyroBias: Vec3,
    /** Row-major 3×3 rotation. */
    val level: List<Double>,
    /** Tilt found, for display: rotation about X and about Y, degrees. */
    val rollDeg: Double,
    val pitchDeg: Double,
    val atMillis: Long,
) {
    fun accel(a: Vec3): Vec3 = rotate(a)

    fun gyro(g: Vec3): Vec3 = rotate(g - gyroBias)

    private fun rotate(v: Vec3) = Vec3(
        level[0] * v.x + level[1] * v.y + level[2] * v.z,
        level[3] * v.x + level[4] * v.y + level[5] * v.z,
        level[6] * v.x + level[7] * v.y + level[8] * v.z,
    )

    /** One line, as stored on disk and written into each session's events. */
    fun encode(): String = (listOf(gyroBias.x, gyroBias.y, gyroBias.z) + level + listOf(rollDeg, pitchDeg))
        .joinToString(" ") { String.format(Locale.ROOT, "%.6f", it) } + " $atMillis"

    /** Why a calibration could not be taken. */
    enum class Failure(val text: String) {
        TooFewSamples("Not enough data from the RaceBox. Check the connection and try again."),
        Moving("The car moved. Keep it still, engine off if possible, and try again."),
        NoGravity("The accelerometer did not read about 1 g at rest. Try again."),
    }

    sealed interface Result {
        data class Ok(val calibration: ImuCalibration) : Result
        data class Failed(val failure: Failure) : Result
    }

    companion object {
        /** About 3 s at 25 Hz. */
        const val MinSamples = 75

        /** Limits for "still": standard deviation of each axis. Design choices, not from RaceBox. */
        private const val MaxAccelSd = 0.03
        private const val MaxGyroSd = 0.5
        private const val MinG = 0.85
        private const val MaxG = 1.15
        private const val Eps = 1e-9
        private const val DegPerRad = 180.0 / Math.PI
        private const val Fields = 14

        fun from(samples: List<ImuSample>, atMillis: Long): Result {
            val failure = problem(samples)
            if (failure != null) return Result.Failed(failure)
            val a = mean(samples.map { it.accel })
            return Result.Ok(
                ImuCalibration(
                    gyroBias = mean(samples.map { it.gyro }),
                    level = levelling(a),
                    rollDeg = atan2(a.y, a.z) * DegPerRad,
                    pitchDeg = atan2(-a.x, sqrt(a.y * a.y + a.z * a.z)) * DegPerRad,
                    atMillis = atMillis,
                ),
            )
        }

        private fun problem(samples: List<ImuSample>): Failure? {
            if (samples.size < MinSamples) return Failure.TooFewSamples
            val a = mean(samples.map { it.accel })
            val g = mean(samples.map { it.gyro })
            val still = sd(samples.map { it.accel }, a).let { maxOf(it.x, it.y, it.z) } <= MaxAccelSd &&
                sd(samples.map { it.gyro }, g).let { maxOf(it.x, it.y, it.z) } <= MaxGyroSd
            return when {
                !still -> Failure.Moving
                a.length !in MinG..MaxG -> Failure.NoGravity
                else -> null
            }
        }

        fun decode(line: String): ImuCalibration? {
            val parts = line.trim().split(' ')
            val v = parts.take(Fields).mapNotNull { it.toDoubleOrNull() }
            val at = parts.lastOrNull()?.toLongOrNull()
            return if (parts.size != Fields + 1 || v.size != Fields || at == null) {
                null
            } else {
                ImuCalibration(Vec3(v[0], v[1], v[2]), v.subList(3, 12), v[12], v[13], at)
            }
        }

        /** Rodrigues rotation taking the unit vector of [gravity] onto +Z. */
        private fun levelling(gravity: Vec3): List<Double> {
            val n = gravity.length
            val u = Vec3(gravity.x / n, gravity.y / n, gravity.z / n)
            // Axis u × z = (u.y, -u.x, 0); angle between u and z.
            val kx = u.y
            val ky = -u.x
            val s = sqrt(kx * kx + ky * ky)
            if (s < Eps) return listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            val x = kx / s
            val y = ky / s
            val angle = acos(u.z.coerceIn(-1.0, 1.0))
            val c = cos(angle)
            val sn = sin(angle)
            val t = 1 - c
            return listOf(
                t * x * x + c, t * x * y, sn * y,
                t * x * y, t * y * y + c, -sn * x,
                -sn * y, sn * x, c,
            )
        }

        private fun mean(v: List<Vec3>) =
            Vec3(v.sumOf { it.x } / v.size, v.sumOf { it.y } / v.size, v.sumOf { it.z } / v.size)

        private fun sd(v: List<Vec3>, m: Vec3): Vec3 {
            fun axis(f: (Vec3) -> Double, mean: Double) =
                sqrt(v.sumOf { (f(it) - mean).let { d -> d * d } } / v.size)
            return Vec3(axis({ it.x }, m.x), axis({ it.y }, m.y), axis({ it.z }, m.z))
        }
    }
}
