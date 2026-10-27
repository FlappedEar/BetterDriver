@file:Suppress("MagicNumber") // Made-up track geometry.

package com.flappedear.betterdriver.recorder.fake

import com.flappedear.betterdriver.recorder.ChannelValue
import com.flappedear.betterdriver.recorder.Gate
import com.flappedear.betterdriver.recorder.GnssChannels
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// The JDK's Math.toRadians and Math.toDegrees factors, so fix n stays the same double on every platform.
private const val RadiansPerDegree = PI / 180.0
private const val DegreesPerRadian = 180.0 / PI

/**
 * Deterministic stand-in for a GNSS receiver: fixes around a circular "track", so tests and the
 * alpha build can record without hardware. Fix n is always the same, which lets tests check that a
 * recovered session holds exactly the fixes that were sent.
 */
class FakeGnss(
    override val rateHz: Int = 25,
    val centerLat: Double = 52.0,
    val centerLon: Double = 21.0,
    val radiusM: Double = 200.0,
    val lapSeconds: Double = 40.0,
    override val startUtcMillis: Long = 1_760_169_600_000L, // 2025-10-11T08:00:00Z
) : GnssScript {
    private val mPerDegLat = 111_320.0
    private val mPerDegLon = 111_320.0 * cos(centerLat * RadiansPerDegree)

    /** A start/finish line across the track at angle 0 (due east of the centre). */
    val startFinish: Gate
        get() {
            val inner = radiusM - 15
            val outer = radiusM + 15
            return Gate(
                "Start / Finish",
                centerLat, centerLon + inner / mPerDegLon,
                centerLat, centerLon + outer / mPerDegLon,
            )
        }

    override fun fix(n: Long): List<ChannelValue> {
        val t = n.toDouble() / rateHz
        val angle = 2 * PI * t / lapSeconds
        val lat = centerLat + radiusM * sin(angle) / mPerDegLat
        val lon = centerLon + radiusM * cos(angle) / mPerDegLon
        val speedKmh = 2 * PI * radiusM / lapSeconds * 3.6
        // Counter-clockwise travel: heading is the tangent, measured clockwise from north.
        val heading = ((-angle * DegreesPerRadian % 360.0) + 360.0) % 360.0
        return listOf(
            ChannelValue(GnssChannels.UTC_MS.id, utcMillis(n).toDouble()),
            ChannelValue(GnssChannels.LAT.id, lat),
            ChannelValue(GnssChannels.LON.id, lon),
            ChannelValue(GnssChannels.SPEED_KMH.id, speedKmh),
            ChannelValue(GnssChannels.HEADING.id, heading),
            ChannelValue(GnssChannels.ALT_M.id, 100.0 + 2 * sin(angle)),
            ChannelValue(GnssChannels.SATS.id, 14.0),
        )
    }
}
